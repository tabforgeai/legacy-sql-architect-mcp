package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.function.BiFunction;

/**
 * MCP tool: dependency_graph
 *
 * Builds a dependency graph of database objects: tables, triggers, and procedures/functions.
 * The graph shows HOW objects are connected, which lets the AI agent trace execution flows
 * like: "table X → trigger T fires → calls procedure P → writes to table Y".
 *
 * This is the key tool for understanding legacy databases where business logic
 * is distributed across stored procedures and triggers rather than in application code.
 *
 * IMPORTANT: All dependency information is retrieved from system catalogs ONLY.
 * No SQL text parsing is performed. This means:
 *   - FK relationships: reliable, from information_schema (standard SQL)
 *   - Trigger → function links: reliable, from pg_trigger / sys.triggers
 *   - Procedure → object references: available for SQL Server via
 *     sys.sql_expression_dependencies (compiler-populated, not text-parsed).
 *     Not available for PostgreSQL without text parsing (out of scope).
 *
 * Tool input parameters:
 *   - schema       (optional) Database schema. Defaults to config.json db_schema.
 *   - table_filter (optional) SQL LIKE pattern to limit graph to specific tables
 *                  (e.g., "ORD%" shows only order-related tables and their dependencies).
 *
 * Tool output:
 *   JSON object with:
 *   - schema:  the inspected schema
 *   - nodes:   list of {id, type} - all objects in the graph (TABLE, TRIGGER, FUNCTION/PROCEDURE)
 *   - edges:   list of {from, from_type, to, to_type, relationship, detail}
 *              relationship types: "FK", "TRIGGER", "CALLS", "REFERENCES"
 *   - summary: counts of tables, triggers, functions, FK edges, trigger edges
 *
 * Example output (abbreviated JSON):
 * <pre>
 * {
 *   "schema": "public",
 *   "nodes": [
 *     { "id": "orders",                  "type": "TABLE"    },
 *     { "id": "customers",               "type": "TABLE"    },
 *     { "id": "trg_before_order_insert", "type": "TRIGGER"  },
 *     { "id": "calculate_discount",      "type": "FUNCTION" }
 *   ],
 *   "edges": [
 *     { "from": "orders", "from_type": "TABLE", "to": "customers",
 *       "to_type": "TABLE", "relationship": "FK", "detail": "via customer_id" },
 *     { "from": "orders", "from_type": "TABLE", "to": "trg_before_order_insert",
 *       "to_type": "TRIGGER", "relationship": "TRIGGER", "detail": "BEFORE INSERT" },
 *     { "from": "trg_before_order_insert", "from_type": "TRIGGER",
 *       "to": "calculate_discount", "to_type": "FUNCTION",
 *       "relationship": "CALLS", "detail": "trigger function" }
 *   ],
 *   "summary": { "table_count": 2, "trigger_count": 1, "function_count": 1,
 *                "fk_edge_count": 1, "trigger_edge_count": 1, "total_edges": 3 }
 * }
 * </pre>
 *
 * Downstream usage - this tool's output is consumed by:
 *   - AI agent directly: traces execution flows to answer "how does order status change?"
 *   - AI agent + get_procedure_source: after seeing which function a trigger calls,
 *     the agent fetches that function's source code for full logic analysis
 *   - generate_mermaid_erd (once implemented): uses nodes/edges to build the flow diagram
 *   - find_impact (once implemented): uses edges in reverse to assess change impact
 */
public class DependencyGraph implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraph.class);

    public static final String TOOL_NAME = "dependency_graph";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final JdbcClient jdbcClient;

    /**
     * Creates a new DependencyGraph tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public DependencyGraph(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for dependency_graph.
     *
     * @param jsonMapper the MCP SDK's JSON mapper for parsing the input schema string
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "schema": {
                      "type": "string",
                      "description": "Database schema name. Defaults to the schema in config.json."
                    },
                    "table_filter": {
                      "type": "string",
                      "description": "Optional SQL LIKE pattern to restrict the graph to specific tables (e.g., 'ORD%'). Only tables matching the pattern and their direct dependencies are included."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Builds a dependency graph showing how database objects are connected: \
                        tables linked via foreign keys, tables with their triggers, and triggers \
                        linked to the functions/procedures they call. \
                        Returns nodes (objects) and edges (relationships) so the AI agent can \
                        trace execution flows and understand the full impact of changes.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the dependency_graph tool when called by the AI agent.
     *
     * Collects FK relationships, trigger-table associations, and trigger-function
     * links from system catalogs, then assembles a unified node/edge graph.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request with optional schema and table_filter
     * @return a CallToolResult containing the dependency graph as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        String schema      = resolveSchema(args);
        String tableFilter = args != null ? (String) args.get("table_filter") : null;
        String tablePattern = (tableFilter == null || tableFilter.isBlank()) ? "%" : tableFilter;

        log.info("Tool '{}' called: schema={}, table_filter={}", TOOL_NAME, schema, tablePattern);

        try {
            // Use LinkedHashSet to preserve insertion order and deduplicate nodes
            Set<String> nodeIds = new LinkedHashSet<>();
            Map<String, String> nodeTypes = new LinkedHashMap<>();
            List<ObjectNode> edges = new ArrayList<>();

            String dbType = jdbcClient.getConfig().getDbType();

            // --- Step 1: FK relationships ---
            // Oracle does not have information_schema.referential_constraints.
            // PostgreSQL and SQL Server share the same information_schema-based query.
            if ("oracle".equalsIgnoreCase(dbType)) {
                collectOracleFkEdges(schema, tablePattern, nodeIds, nodeTypes, edges);
            } else {
                collectFkEdges(schema, tablePattern, nodeIds, nodeTypes, edges);
            }

            // --- Step 2: Trigger → Table and Trigger → Function links ---
            if ("sqlserver".equalsIgnoreCase(dbType)) {
                collectSqlServerTriggers(schema, tablePattern, nodeIds, nodeTypes, edges);
                // SQL Server bonus: procedure → object references from the compiler catalog
                collectSqlServerProcedureDeps(schema, nodeIds, nodeTypes, edges);
            } else if ("oracle".equalsIgnoreCase(dbType)) {
                collectOracleTriggers(schema, tablePattern, nodeIds, nodeTypes, edges);
            } else {
                collectPostgresTriggers(schema, tablePattern, nodeIds, nodeTypes, edges);
            }

            // --- Assemble the final graph ---
            ObjectNode result = JSON.createObjectNode();
            result.put("schema", schema);

            // Nodes array: one entry per unique database object
            ArrayNode nodes = JSON.createArrayNode();
            for (String nodeId : nodeIds) {
                ObjectNode node = JSON.createObjectNode();
                node.put("id", nodeId);
                node.put("type", nodeTypes.getOrDefault(nodeId, "UNKNOWN"));
                nodes.add(node);
            }
            result.set("nodes", nodes);

            // Edges array
            ArrayNode edgesArray = JSON.createArrayNode();
            edges.forEach(edgesArray::add);
            result.set("edges", edgesArray);

            // Summary counts
            long tableCount   = nodeTypes.values().stream().filter("TABLE"::equals).count();
            long triggerCount = nodeTypes.values().stream().filter("TRIGGER"::equals).count();
            long funcCount    = nodeTypes.values().stream()
                    .filter(t -> t.equals("FUNCTION") || t.equals("PROCEDURE")).count();
            long fkCount      = edges.stream().filter(e -> "FK".equals(e.get("relationship").asText())).count();
            long trigEdgeCount = edges.stream().filter(e -> "TRIGGER".equals(e.get("relationship").asText())).count();

            ObjectNode summary = JSON.createObjectNode();
            summary.put("table_count",   tableCount);
            summary.put("trigger_count", triggerCount);
            summary.put("function_count", funcCount);
            summary.put("fk_edge_count", fkCount);
            summary.put("trigger_edge_count", trigEdgeCount);
            summary.put("total_edges", edges.size());
            result.set("summary", summary);

            log.info("dependency_graph: {} nodes, {} edges", nodeIds.size(), edges.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("dependency_graph failed: {}", e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error building dependency graph: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * Collects foreign key relationships using information_schema.
     *
     * information_schema.referential_constraints and key_column_usage are standard SQL
     * and work identically on PostgreSQL and SQL Server. Each FK creates:
     *   - Two TABLE nodes (child and parent)
     *   - One FK edge: child_table --[FK via column]--> parent_table
     *
     * Multiple FK columns between the same two tables (composite FK) result in
     * multiple edges, each showing its specific column. This is intentional - the
     * AI agent sees the full picture of which column carries the relationship.
     *
     * @param schema       the schema to inspect
     * @param tablePattern SQL LIKE pattern to filter child tables
     * @param nodeIds      set to accumulate node IDs (deduplicated)
     * @param nodeTypes    map from node ID to type string
     * @param edges        list to accumulate edge objects
     * @throws SQLException if the query fails
     */
    private void collectFkEdges(String schema, String tablePattern,
                                 Set<String> nodeIds, Map<String, String> nodeTypes,
                                 List<ObjectNode> edges) throws SQLException {
        // This query is valid for both PostgreSQL and SQL Server.
        // It joins three information_schema views to get: child_table.child_column → parent_table.parent_column
        String sql = """
                SELECT kcu.table_name        AS child_table,
                       kcu.column_name       AS child_column,
                       ccu.table_name        AS parent_table,
                       ccu.column_name       AS parent_column,
                       tc.constraint_name    AS constraint_name
                FROM   information_schema.table_constraints      AS tc
                JOIN   information_schema.key_column_usage       AS kcu
                       ON  tc.constraint_name = kcu.constraint_name
                       AND tc.table_schema    = kcu.table_schema
                JOIN   information_schema.constraint_column_usage AS ccu
                       ON  ccu.constraint_name = tc.constraint_name
                       AND ccu.table_schema    = tc.table_schema
                WHERE  tc.constraint_type = 'FOREIGN KEY'
                  AND  tc.table_schema    = ?
                  AND  kcu.table_name LIKE ?
                ORDER  BY kcu.table_name, kcu.column_name
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String childTable  = rs.getString("child_table");
                    String childColumn = rs.getString("child_column");
                    String parentTable = rs.getString("parent_table");

                    registerNode(childTable,  "TABLE", nodeIds, nodeTypes);
                    registerNode(parentTable, "TABLE", nodeIds, nodeTypes);

                    ObjectNode edge = JSON.createObjectNode();
                    edge.put("from",          childTable);
                    edge.put("from_type",     "TABLE");
                    edge.put("to",            parentTable);
                    edge.put("to_type",       "TABLE");
                    edge.put("relationship",  "FK");
                    edge.put("detail",        "via " + childColumn);
                    edges.add(edge);
                }
            }
        }
        log.debug("FK edges collected: {}", edges.size());
    }

    /**
     * Collects trigger → table and trigger → function relationships for PostgreSQL.
     *
     * Uses pg_trigger (trigger catalog), pg_class (table/relation catalog),
     * pg_proc (function catalog), and pg_namespace (schema catalog).
     *
     * pg_trigger.tgfoid directly references the pg_proc OID of the trigger function,
     * so we get the exact function name without any text parsing.
     *
     * The tgtype bitmask is decoded in Java to produce a human-readable event string
     * (e.g., "BEFORE INSERT", "AFTER UPDATE, DELETE").
     *
     * tgisinternal=true filters out constraint triggers created automatically by
     * PostgreSQL for FK constraints - those are implementation details, not business logic.
     *
     * @param schema       the PostgreSQL schema name
     * @param tablePattern SQL LIKE pattern to filter tables
     * @param nodeIds      set to accumulate node IDs
     * @param nodeTypes    map from node ID to type string
     * @param edges        list to accumulate edge objects
     * @throws SQLException if the query fails
     */
    private void collectPostgresTriggers(String schema, String tablePattern,
                                          Set<String> nodeIds, Map<String, String> nodeTypes,
                                          List<ObjectNode> edges) throws SQLException {
        String sql = """
                SELECT t.tgname   AS trigger_name,
                       c.relname  AS table_name,
                       p.proname  AS function_name,
                       t.tgtype   AS tgtype
                FROM   pg_trigger   t
                JOIN   pg_class     c ON c.oid = t.tgrelid
                JOIN   pg_proc      p ON p.oid = t.tgfoid
                JOIN   pg_namespace n ON n.oid = c.relnamespace
                WHERE  n.nspname       = ?
                  AND  c.relname      LIKE ?
                  AND  NOT t.tgisinternal
                ORDER  BY c.relname, t.tgname
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String triggerName  = rs.getString("trigger_name");
                    String tableName    = rs.getString("table_name");
                    String functionName = rs.getString("function_name");
                    int    tgtype       = rs.getInt("tgtype");

                    String timing = decodePgTriggerTiming(tgtype);
                    String events = decodePgTriggerEvents(tgtype);

                    registerNode(tableName,    "TABLE",    nodeIds, nodeTypes);
                    registerNode(triggerName,  "TRIGGER",  nodeIds, nodeTypes);
                    registerNode(functionName, "FUNCTION", nodeIds, nodeTypes);

                    // Edge 1: table → trigger (the trigger is defined ON this table)
                    ObjectNode tableToTrigger = JSON.createObjectNode();
                    tableToTrigger.put("from",         tableName);
                    tableToTrigger.put("from_type",    "TABLE");
                    tableToTrigger.put("to",           triggerName);
                    tableToTrigger.put("to_type",      "TRIGGER");
                    tableToTrigger.put("relationship", "TRIGGER");
                    tableToTrigger.put("detail",       timing + " " + events);
                    edges.add(tableToTrigger);

                    // Edge 2: trigger → function (the trigger calls this function)
                    ObjectNode triggerToFunc = JSON.createObjectNode();
                    triggerToFunc.put("from",         triggerName);
                    triggerToFunc.put("from_type",    "TRIGGER");
                    triggerToFunc.put("to",           functionName);
                    triggerToFunc.put("to_type",      "FUNCTION");
                    triggerToFunc.put("relationship", "CALLS");
                    triggerToFunc.put("detail",       "trigger function");
                    edges.add(triggerToFunc);
                }
            }
        }
    }

    /**
     * Collects trigger → table relationships for SQL Server using sys.triggers.
     *
     * sys.triggers provides: trigger name, parent object ID, type, enabled status.
     * OBJECT_NAME(parent_id) gives the table name the trigger is defined on.
     *
     * Unlike PostgreSQL, SQL Server triggers do not have a direct catalog link to
     * specific called procedures (the trigger body is T-SQL inline code, not a
     * separate function). For this reason, only the table → trigger edge is created.
     * Procedure references from trigger bodies are covered by collectSqlServerProcedureDeps().
     *
     * @param schema       the SQL Server schema name
     * @param tablePattern SQL LIKE pattern to filter parent tables
     * @param nodeIds      set to accumulate node IDs
     * @param nodeTypes    map from node ID to type string
     * @param edges        list to accumulate edge objects
     * @throws SQLException if the query fails
     */
    private void collectSqlServerTriggers(String schema, String tablePattern,
                                           Set<String> nodeIds, Map<String, String> nodeTypes,
                                           List<ObjectNode> edges) throws SQLException {
        String sql = """
                SELECT t.name                     AS trigger_name,
                       OBJECT_NAME(t.parent_id)   AS table_name,
                       t.type_desc                AS trigger_type,
                       CASE WHEN t.is_disabled = 1 THEN 'DISABLED' ELSE 'ENABLED' END AS status
                FROM   sys.triggers t
                JOIN   sys.objects  o ON o.object_id = t.parent_id
                WHERE  SCHEMA_NAME(o.schema_id) = ?
                  AND  OBJECT_NAME(t.parent_id) LIKE ?
                ORDER  BY OBJECT_NAME(t.parent_id), t.name
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String triggerName = rs.getString("trigger_name");
                    String tableName   = rs.getString("table_name");
                    String status      = rs.getString("status");

                    registerNode(tableName,   "TABLE",   nodeIds, nodeTypes);
                    registerNode(triggerName, "TRIGGER", nodeIds, nodeTypes);

                    ObjectNode edge = JSON.createObjectNode();
                    edge.put("from",         tableName);
                    edge.put("from_type",    "TABLE");
                    edge.put("to",           triggerName);
                    edge.put("to_type",      "TRIGGER");
                    edge.put("relationship", "TRIGGER");
                    edge.put("detail",       status);
                    edges.add(edge);
                }
            }
        }
    }

    /**
     * Collects procedure/function → table/object references for SQL Server
     * using sys.sql_expression_dependencies.
     *
     * sys.sql_expression_dependencies is populated by SQL Server's query compiler
     * when a procedure or function is created/altered. It represents verified
     * dependencies - NOT text-parsed ones. When a procedure calls another procedure
     * or references a table, SQL Server records it here.
     *
     * This covers:
     *   - Stored procedure → table (procedure reads or writes this table)
     *   - Stored procedure → stored procedure (procedure calls another procedure)
     *   - Trigger → table (additional table references in trigger body)
     *
     * We filter to same-database dependencies (referenced_database_name IS NULL)
     * and only include dependencies where the source is in the target schema.
     *
     * @param schema    the SQL Server schema name
     * @param nodeIds   set to accumulate node IDs
     * @param nodeTypes map from node ID to type string
     * @param edges     list to accumulate edge objects
     * @throws SQLException if the query fails
     */
    private void collectSqlServerProcedureDeps(String schema,
                                                Set<String> nodeIds, Map<String, String> nodeTypes,
                                                List<ObjectNode> edges) throws SQLException {
        String sql = """
                SELECT OBJECT_NAME(d.referencing_id)                 AS source_name,
                       o_src.type_desc                                AS source_type,
                       d.referenced_entity_name                       AS target_name,
                       COALESCE(d.referenced_schema_name, ?)          AS target_schema,
                       COALESCE(o_tgt.type_desc, 'UNKNOWN')           AS target_type
                FROM   sys.sql_expression_dependencies d
                JOIN   sys.objects o_src
                       ON  o_src.object_id = d.referencing_id
                LEFT JOIN sys.objects o_tgt
                       ON  o_tgt.name      = d.referenced_entity_name
                       AND SCHEMA_NAME(o_tgt.schema_id) = COALESCE(d.referenced_schema_name, ?)
                WHERE  SCHEMA_NAME(o_src.schema_id) = ?
                  AND  d.referenced_database_name IS NULL
                  AND  o_src.type IN ('P', 'FN', 'IF', 'TF', 'TR')
                ORDER  BY source_name, target_name
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);  // fallback for referenced_schema_name IS NULL
            ps.setString(2, schema);  // same fallback for the LEFT JOIN
            ps.setString(3, schema);  // filter: source object is in our schema
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sourceName  = rs.getString("source_name");
                    String sourceType  = mapSqlServerType(rs.getString("source_type"));
                    String targetName  = rs.getString("target_name");
                    String targetType  = mapSqlServerType(rs.getString("target_type"));

                    // Skip if this edge was already captured as a FK or trigger edge
                    if (sourceName == null || targetName == null) continue;

                    registerNode(sourceName, sourceType, nodeIds, nodeTypes);
                    registerNode(targetName, targetType, nodeIds, nodeTypes);

                    ObjectNode edge = JSON.createObjectNode();
                    edge.put("from",         sourceName);
                    edge.put("from_type",    sourceType);
                    edge.put("to",           targetName);
                    edge.put("to_type",      targetType);
                    edge.put("relationship", "REFERENCES");
                    edge.put("detail",       "compiler-verified dependency");
                    edges.add(edge);
                }
            }
        }
    }

    /**
     * Collects foreign key relationships for Oracle using ALL_CONSTRAINTS and ALL_CONS_COLUMNS.
     *
     * Oracle does not have information_schema.referential_constraints, so this method
     * uses Oracle-specific data dictionary views instead:
     * <ul>
     *   <li>{@code ALL_CONSTRAINTS}   — constraint definitions (type 'R' = referential/FK)</li>
     *   <li>{@code ALL_CONS_COLUMNS}  — column-level constraint details</li>
     * </ul>
     *
     * The join logic:
     * <ol>
     *   <li>Find FK constraints (type='R') in the child table (ac)</li>
     *   <li>Get the FK column(s) from ALL_CONS_COLUMNS (acc)</li>
     *   <li>Follow the r_constraint_name to the parent constraint (ac2)</li>
     * </ol>
     *
     * Schema and table name comparisons use UPPER() because Oracle stores object
     * names in uppercase unless they were created with quoted identifiers.
     * The table_filter pattern is also uppercased to match Oracle's naming convention.
     *
     * @param schema       the Oracle schema (owner) name
     * @param tablePattern SQL LIKE pattern to filter child tables (compared in UPPER case)
     * @param nodeIds      set to accumulate node IDs (deduplicated)
     * @param nodeTypes    map from node ID to type string
     * @param edges        list to accumulate edge objects
     * @throws SQLException if the query fails
     */
    private void collectOracleFkEdges(String schema, String tablePattern,
                                       Set<String> nodeIds, Map<String, String> nodeTypes,
                                       List<ObjectNode> edges) throws SQLException {
        String sql = """
                SELECT ac.table_name   AS child_table,
                       acc.column_name AS child_column,
                       ac2.table_name  AS parent_table
                FROM   ALL_CONSTRAINTS  ac
                JOIN   ALL_CONS_COLUMNS acc  ON acc.constraint_name = ac.constraint_name
                                            AND acc.owner           = ac.owner
                JOIN   ALL_CONSTRAINTS  ac2  ON ac2.constraint_name = ac.r_constraint_name
                                            AND ac2.owner           = ac.r_owner
                WHERE  ac.constraint_type = 'R'
                  AND  ac.owner           = UPPER(?)
                  AND  ac.table_name      LIKE UPPER(?)
                ORDER  BY ac.table_name, acc.column_name
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String childTable  = rs.getString("child_table");
                    String childColumn = rs.getString("child_column");
                    String parentTable = rs.getString("parent_table");

                    registerNode(childTable,  "TABLE", nodeIds, nodeTypes);
                    registerNode(parentTable, "TABLE", nodeIds, nodeTypes);

                    ObjectNode edge = JSON.createObjectNode();
                    edge.put("from",         childTable);
                    edge.put("from_type",    "TABLE");
                    edge.put("to",           parentTable);
                    edge.put("to_type",      "TABLE");
                    edge.put("relationship", "FK");
                    edge.put("detail",       "via " + childColumn);
                    edges.add(edge);
                }
            }
        }
        log.debug("Oracle FK edges collected: {}", edges.size());
    }

    /**
     * Collects trigger → table relationships for Oracle using ALL_TRIGGERS,
     * then follows up with trigger → procedure/function links via ALL_DEPENDENCIES.
     *
     * This is a two-phase approach:
     * <ol>
     *   <li>Phase 1 ({@link #collectOracleTriggers}): builds TABLE → TRIGGER edges
     *       from ALL_TRIGGERS, similar to the PostgreSQL approach.</li>
     *   <li>Phase 2 ({@link #collectOracleTriggerDeps}): uses ALL_DEPENDENCIES
     *       (type='TRIGGER') to find the procedures/functions/packages that each
     *       trigger calls, producing TRIGGER → PROCEDURE/FUNCTION/PACKAGE edges.</li>
     * </ol>
     *
     * Unlike PostgreSQL (where pg_trigger.tgfoid directly links to a trigger function),
     * Oracle triggers can call any number of PL/SQL objects. ALL_DEPENDENCIES tracks
     * these references with compiler-verified accuracy.
     *
     * @param schema       the Oracle schema (owner) name
     * @param tablePattern SQL LIKE pattern to filter tables (compared in UPPER case)
     * @param nodeIds      set to accumulate node IDs
     * @param nodeTypes    map from node ID to type string
     * @param edges        list to accumulate edge objects
     * @throws SQLException if either query fails
     */
    private void collectOracleTriggers(String schema, String tablePattern,
                                        Set<String> nodeIds, Map<String, String> nodeTypes,
                                        List<ObjectNode> edges) throws SQLException {
        // Phase 1: TABLE → TRIGGER edges
        String sql = """
                SELECT TRIGGER_NAME     AS trigger_name,
                       TABLE_NAME       AS table_name,
                       TRIGGER_TYPE     AS trigger_type,
                       TRIGGERING_EVENT AS events
                FROM   ALL_TRIGGERS
                WHERE  OWNER      = UPPER(?)
                  AND  TABLE_NAME LIKE UPPER(?)
                ORDER  BY TABLE_NAME, TRIGGER_NAME
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String triggerName = rs.getString("trigger_name");
                    String tableName   = rs.getString("table_name");
                    String triggerType = rs.getString("trigger_type"); // e.g. "BEFORE EACH ROW"
                    String events      = rs.getString("events");       // e.g. "INSERT OR UPDATE"

                    registerNode(tableName,   "TABLE",   nodeIds, nodeTypes);
                    registerNode(triggerName, "TRIGGER", nodeIds, nodeTypes);

                    ObjectNode edge = JSON.createObjectNode();
                    edge.put("from",         tableName);
                    edge.put("from_type",    "TABLE");
                    edge.put("to",           triggerName);
                    edge.put("to_type",      "TRIGGER");
                    edge.put("relationship", "TRIGGER");
                    edge.put("detail",       triggerType + " " + events);
                    edges.add(edge);
                }
            }
        }

        // Phase 2: TRIGGER → PROCEDURE/FUNCTION/PACKAGE CALLS edges
        collectOracleTriggerDeps(schema, tablePattern, nodeIds, nodeTypes, edges);
    }

    /**
     * Collects trigger → procedure/function/package CALLS edges for Oracle
     * using ALL_DEPENDENCIES.
     *
     * ALL_DEPENDENCIES is populated by Oracle's PL/SQL compiler when a trigger
     * is created or recompiled. It records every named PL/SQL object that the
     * trigger body references — including called procedures, functions, and packages.
     *
     * We restrict to triggers on tables that match the table_filter to keep the
     * graph scoped to the user's area of interest. The join to ALL_TRIGGERS on
     * TABLE_NAME ensures this scope is enforced.
     *
     * @param schema       the Oracle schema (owner) name
     * @param tablePattern SQL LIKE pattern used to filter the parent table of each trigger
     * @param nodeIds      set to accumulate node IDs
     * @param nodeTypes    map from node ID to type string
     * @param edges        list to accumulate edge objects
     * @throws SQLException if the query fails
     */
    private void collectOracleTriggerDeps(String schema, String tablePattern,
                                           Set<String> nodeIds, Map<String, String> nodeTypes,
                                           List<ObjectNode> edges) throws SQLException {
        String sql = """
                SELECT d.NAME            AS trigger_name,
                       d.REFERENCED_NAME AS called_name,
                       d.REFERENCED_TYPE AS called_type
                FROM   ALL_DEPENDENCIES d
                JOIN   ALL_TRIGGERS     t ON t.TRIGGER_NAME = d.NAME
                                        AND t.OWNER         = d.OWNER
                WHERE  d.OWNER            = UPPER(?)
                  AND  d.TYPE             = 'TRIGGER'
                  AND  t.TABLE_NAME       LIKE UPPER(?)
                  AND  d.REFERENCED_TYPE  IN ('PROCEDURE', 'FUNCTION', 'PACKAGE')
                ORDER  BY d.NAME, d.REFERENCED_NAME
                """;

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String triggerName = rs.getString("trigger_name");
                    String calledName  = rs.getString("called_name");
                    String calledType  = rs.getString("called_type"); // PROCEDURE, FUNCTION, PACKAGE

                    // Only emit CALLS edges for triggers that are already in the graph.
                    // This prevents dangling edges if a trigger exists but its parent table
                    // was excluded by the table_filter.
                    if (!nodeIds.contains(triggerName)) continue;

                    registerNode(calledName, calledType, nodeIds, nodeTypes);

                    ObjectNode edge = JSON.createObjectNode();
                    edge.put("from",         triggerName);
                    edge.put("from_type",    "TRIGGER");
                    edge.put("to",           calledName);
                    edge.put("to_type",      calledType);
                    edge.put("relationship", "CALLS");
                    edge.put("detail",       "compiler-verified dependency");
                    edges.add(edge);
                }
            }
        }
    }

    /**
     * Decodes the PostgreSQL tgtype bitmask into a timing string.
     *
     * PostgreSQL trigger type bits (defined in pg_trigger.h):
     *   Bit 1  (0x01): Row-level trigger (vs statement-level)
     *   Bit 2  (0x02): BEFORE timing (absence = AFTER)
     *   Bit 7  (0x40): INSTEAD OF timing
     *
     * @param tgtype the raw tgtype integer from pg_trigger
     * @return "BEFORE", "AFTER", or "INSTEAD OF"
     */
    private String decodePgTriggerTiming(int tgtype) {
        if ((tgtype & 0x40) != 0) return "INSTEAD OF";
        if ((tgtype & 0x02) != 0) return "BEFORE";
        return "AFTER";
    }

    /**
     * Decodes the PostgreSQL tgtype bitmask into a comma-separated event string.
     *
     * PostgreSQL trigger event bits (defined in pg_trigger.h):
     *   Bit 3  (0x04): INSERT
     *   Bit 4  (0x08): DELETE
     *   Bit 5  (0x10): UPDATE
     *   Bit 6  (0x20): TRUNCATE
     *
     * @param tgtype the raw tgtype integer from pg_trigger
     * @return comma-separated string of events, e.g., "INSERT, UPDATE"
     */
    private String decodePgTriggerEvents(int tgtype) {
        List<String> events = new ArrayList<>();
        if ((tgtype & 0x04) != 0) events.add("INSERT");
        if ((tgtype & 0x08) != 0) events.add("DELETE");
        if ((tgtype & 0x10) != 0) events.add("UPDATE");
        if ((tgtype & 0x20) != 0) events.add("TRUNCATE");
        return events.isEmpty() ? "UNKNOWN" : String.join(", ", events);
    }

    /**
     * Maps SQL Server's type_desc strings to shorter, consistent type labels
     * used in the graph nodes.
     *
     * @param sqlServerTypeDesc the type_desc value from sys.objects (e.g., "SQL_STORED_PROCEDURE")
     * @return simplified type label for the graph node
     */
    private String mapSqlServerType(String sqlServerTypeDesc) {
        if (sqlServerTypeDesc == null) return "UNKNOWN";
        return switch (sqlServerTypeDesc.toUpperCase()) {
            case "SQL_STORED_PROCEDURE",
                 "CLR_STORED_PROCEDURE"     -> "PROCEDURE";
            case "SQL_SCALAR_FUNCTION",
                 "SQL_INLINE_TABLE_VALUED_FUNCTION",
                 "SQL_TABLE_VALUED_FUNCTION",
                 "CLR_SCALAR_FUNCTION"      -> "FUNCTION";
            case "SQL_TRIGGER",
                 "CLR_TRIGGER"              -> "TRIGGER";
            case "USER_TABLE"               -> "TABLE";
            case "VIEW"                     -> "VIEW";
            default                         -> sqlServerTypeDesc;
        };
    }

    /**
     * Registers a node in the graph collections.
     *
     * If the node ID is already registered with a type, this is a no-op
     * (first registration wins). This prevents overwriting a "TABLE" type
     * with "UNKNOWN" if the same object is encountered from different queries.
     *
     * @param id        the node identifier (object name)
     * @param type      the node type ("TABLE", "TRIGGER", "FUNCTION", "PROCEDURE")
     * @param nodeIds   the ordered set of registered node IDs
     * @param nodeTypes the map from node ID to type
     */
    private void registerNode(String id, String type, Set<String> nodeIds, Map<String, String> nodeTypes) {
        nodeIds.add(id);
        nodeTypes.putIfAbsent(id, type);
    }

    /**
     * Resolves the schema name from request arguments or config default.
     *
     * @param args the tool call arguments map (may be null)
     * @return the schema name to use
     */
    private String resolveSchema(Map<String, Object> args) {
        if (args != null && args.get("schema") instanceof String s && !s.isBlank()) {
            return s;
        }
        return jdbcClient.getConfig().getDbSchema();
    }
}
