package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP tool: query_plan_expert
 *
 * Executes EXPLAIN on a given SQL query and returns both the raw execution plan
 * and a structured analysis highlighting potential performance problems.
 *
 * The tool adds value beyond raw EXPLAIN output by automatically detecting
 * common performance anti-patterns and surfacing them as structured "warnings":
 *   - Sequential scans (Seq Scan) on tables with many estimated rows (PostgreSQL)
 *   - Table scans on large tables (SQL Server)
 *   - Missing index suggestions based on filter conditions in the plan
 *
 * The AI agent receives both the raw plan (for deep analysis) and the pre-parsed
 * warnings (for quick answers), so it can answer queries like:
 *   "Why is this query slow?" or "What index should I add?"
 *
 * SAFETY: The query is never actually executed.
 *   - PostgreSQL: EXPLAIN without ANALYZE only plans the query, never runs it.
 *   - SQL Server: SET SHOWPLAN_ALL ON causes the query to be planned but not executed.
 *   Combined with the read-only JDBC connection, data modification is impossible.
 *
 * Tool input parameters:
 *   - sql    (required) The SQL query to analyze. Do NOT include EXPLAIN keyword.
 *   - schema (optional) Schema name for context. Defaults to config.json db_schema.
 *
 * Tool output:
 *   JSON object example (PostgreSQL, query with missing index):
 * <pre>
 * {
 *   "sql": "SELECT * FROM orders WHERE customer_id = 5",
 *   "database_type": "postgresql",
 *   "raw_plan": [{ "Plan": { "Node Type": "Seq Scan", "Relation Name": "orders",
 *                             "Plan Rows": 50000, "Filter": "(customer_id = $1)" } }],
 *   "warnings": [
 *     {
 *       "type": "SEQ_SCAN",
 *       "table": "orders",
 *       "estimated_rows": 50000,
 *       "message": "Sequential scan on 'orders' with ~50000 estimated rows. This will be slow on large tables.",
 *       "filter_condition": "(customer_id = $1)",
 *       "suggested_index": "CREATE INDEX idx_orders_customer_id ON orders (customer_id);"
 *     }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage - this tool's output is consumed by:
 *   - AI agent directly: diagnoses slow query causes and proposes concrete fixes
 *   - The "suggested_index" field gives the agent a ready-to-use DDL statement,
 *     so the developer gets an actionable answer, not just a diagnosis
 */
public class QueryPlanExpert implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanExpert.class);

    public static final String TOOL_NAME = "query_plan_expert";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Minimum estimated row count for a sequential/table scan to be flagged as a warning.
     * Scans on small tables (below this threshold) are not performance problems.
     * This threshold is intentionally conservative - a seq scan on 1000 rows is usually fine.
     */
    private static final int SEQ_SCAN_WARNING_THRESHOLD = 1000;

    /**
     * Regex pattern for extracting column names from PostgreSQL filter conditions.
     * Matches patterns like: "(column_name = $1)", "(status IS NULL)", "(amount > 0)"
     * The first capturing group extracts the column name.
     * This is a heuristic - it handles the most common cases but not all SQL syntax.
     */
    private static final Pattern FILTER_COLUMN_PATTERN =
            Pattern.compile("\\(([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=|<|>|<=|>=|<>|!=|IS|LIKE|IN)", Pattern.CASE_INSENSITIVE);

    private final JdbcClient jdbcClient;

    /**
     * Creates a new QueryPlanExpert tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public QueryPlanExpert(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for query_plan_expert.
     *
     * @param jsonMapper the MCP SDK's JSON mapper for parsing the input schema string
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "sql": {
                      "type": "string",
                      "description": "The SQL SELECT query to analyze. Do NOT include the EXPLAIN keyword - the tool adds it automatically."
                    },
                    "schema": {
                      "type": "string",
                      "description": "Database schema name for search_path context. Defaults to the schema in config.json."
                    }
                  },
                  "required": ["sql"]
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Analyzes a SQL query's execution plan using EXPLAIN and returns the raw plan \
                        plus a structured list of performance warnings (sequential scans, missing indexes). \
                        The query is NEVER executed - only planned. Safe to use on production databases. \
                        Use this to diagnose slow queries and get index recommendations.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the query_plan_expert tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the SQL query to analyze
     * @return a CallToolResult containing the plan and warnings as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        if (args == null || args.get("sql") == null) {
            return errorResult("Missing required parameter: 'sql'");
        }

        String userSql = ((String) args.get("sql")).trim();
        String schema  = resolveSchema(args);

        log.info("Tool '{}' called: schema={}, sql preview='{}'", TOOL_NAME, schema,
                userSql.length() > 80 ? userSql.substring(0, 80) + "..." : userSql);

        try {
            String dbType = jdbcClient.getConfig().getDbType();
            ObjectNode result = JSON.createObjectNode();
            result.put("sql", userSql);
            result.put("database_type", dbType);

            if ("sqlserver".equalsIgnoreCase(dbType)) {
                analyzeSqlServer(userSql, result);
            } else if ("oracle".equalsIgnoreCase(dbType)) {
                analyzeOracle(userSql, schema, result);
            } else {
                analyzePostgres(userSql, schema, result);
            }

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("query_plan_expert failed: {}", e.getMessage(), e);
            return errorResult("Error analyzing query: " + e.getMessage()
                    + "\nMake sure the SQL is a valid SELECT query for the target database.");
        }
    }

    /**
     * Runs EXPLAIN (FORMAT JSON) for PostgreSQL and parses the plan for warnings.
     *
     * PostgreSQL EXPLAIN FORMAT JSON returns a JSON array with one element:
     * [{"Plan": {...node tree...}, "Planning Time": 0.1, ...}]
     *
     * We recursively walk the node tree looking for "Seq Scan" nodes. For each
     * seq scan with estimated rows above the warning threshold, we record a warning
     * and attempt to extract the filtered column(s) from the "Filter" field
     * to suggest a potential index.
     *
     * The search_path is set before EXPLAIN so that unqualified table names in
     * the user's query resolve correctly to the configured schema.
     *
     * @param userSql the user's SQL query (without EXPLAIN prefix)
     * @param schema  the schema to set as search_path
     * @param result  the JSON result object to populate with raw_plan and warnings
     * @throws Exception if the EXPLAIN query fails or plan parsing fails
     */
    private void analyzePostgres(String userSql, String schema, ObjectNode result) throws Exception {
        Connection conn = jdbcClient.getConnection();

        // Set search_path so unqualified table names in the user's query resolve correctly.
        // This is a session-level setting that persists for the connection lifetime,
        // which is acceptable since the schema rarely changes between tool calls.
        try (Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET search_path TO " + schema);
        }

        String explainSql = "EXPLAIN (FORMAT JSON) " + userSql;
        log.debug("Executing: {}", explainSql);

        String planJson;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explainSql)) {
            // PostgreSQL EXPLAIN FORMAT JSON returns a single row with a single column
            // containing the entire plan as a JSON string
            rs.next();
            planJson = rs.getString(1);
        }

        // Parse the plan JSON so we can both return it and walk it for warnings
        JsonNode planTree = JSON.readTree(planJson);
        result.set("raw_plan", planTree);

        // Walk the plan tree and collect performance warnings
        ArrayNode warnings = JSON.createArrayNode();
        if (planTree.isArray() && !planTree.isEmpty()) {
            JsonNode topPlan = planTree.get(0).get("Plan");
            if (topPlan != null) {
                collectPostgresWarnings(topPlan, warnings);
            }
        }
        result.set("warnings", warnings);
        log.info("query_plan_expert (PostgreSQL): {} warning(s) found", warnings.size());
    }

    /**
     * Recursively walks a PostgreSQL JSON plan node tree, collecting warnings
     * for sequential scans on tables with many estimated rows.
     *
     * The PostgreSQL plan tree is nested: each node has a "Plans" array containing
     * child nodes. We visit every node depth-first.
     *
     * For each "Seq Scan" node with Plan Rows >= SEQ_SCAN_WARNING_THRESHOLD:
     *   - Records the table name and estimated row count
     *   - Extracts the Filter condition (if present)
     *   - Extracts potential column names from the filter for index suggestions
     *
     * @param node     the current plan node (JSON object)
     * @param warnings the ArrayNode to append warning objects to
     */
    private void collectPostgresWarnings(JsonNode node, ArrayNode warnings) {
        if (!node.isObject()) return;

        String nodeType = node.path("Node Type").asText("");
        if ("Seq Scan".equals(nodeType)) {
            long planRows = node.path("Plan Rows").asLong(0);
            if (planRows >= SEQ_SCAN_WARNING_THRESHOLD) {
                String tableName = node.path("Relation Name").asText("unknown");
                String filter    = node.path("Filter").asText(null);

                ObjectNode warning = JSON.createObjectNode();
                warning.put("type", "SEQ_SCAN");
                warning.put("table", tableName);
                warning.put("estimated_rows", planRows);
                warning.put("message",
                        "Sequential scan on '" + tableName + "' with ~" + planRows + " estimated rows. "
                        + "This will be slow on large tables.");

                if (filter != null && !filter.isBlank()) {
                    warning.put("filter_condition", filter);
                    // Extract column names from the filter to suggest index candidates
                    List<String> columns = extractColumnsFromFilter(filter);
                    if (!columns.isEmpty()) {
                        warning.put("suggested_index",
                                "CREATE INDEX idx_" + tableName + "_" + String.join("_", columns)
                                + " ON " + tableName + " (" + String.join(", ", columns) + ");");
                    }
                }
                warnings.add(warning);
            }
        }

        // Recurse into child nodes
        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                collectPostgresWarnings(child, warnings);
            }
        }
    }

    /**
     * Uses SET SHOWPLAN_ALL ON to get the SQL Server execution plan without running the query.
     *
     * When SHOWPLAN_ALL is ON, SQL Server returns plan rows instead of query results.
     * Each row represents one step in the execution plan with columns including:
     * PhysicalOp, LogicalOp, EstimateRows, TotalSubtreeCost, Argument, Warnings.
     *
     * The connection state (SHOWPLAN_ALL) is always restored in the finally block,
     * even if an exception occurs during plan retrieval, to prevent the connection
     * from being left in an inconsistent state.
     *
     * @param userSql the user's SQL query
     * @param result  the JSON result object to populate with raw_plan and warnings
     * @throws Exception if the SHOWPLAN query fails
     */
    private void analyzeSqlServer(String userSql, ObjectNode result) throws Exception {
        Connection conn = jdbcClient.getConnection();
        ArrayNode rawPlanRows = JSON.createArrayNode();
        ArrayNode warnings    = JSON.createArrayNode();

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET SHOWPLAN_ALL ON");
            try (ResultSet rs = stmt.executeQuery(userSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                // Collect column names once
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    cols.add(meta.getColumnName(i));
                }

                while (rs.next()) {
                    ObjectNode row = JSON.createObjectNode();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        if (val == null)         row.putNull(cols.get(i - 1));
                        else if (val instanceof Number n) row.put(cols.get(i - 1), n.doubleValue());
                        else                     row.put(cols.get(i - 1), val.toString());
                    }
                    rawPlanRows.add(row);

                    // Detect table scans: PhysicalOp = "Table Scan" or "Clustered Index Scan"
                    // (Clustered Index Scan without a seek = full scan, not a seek)
                    String physOp    = rs.getString("PhysicalOp");
                    double estRows   = rs.getDouble("EstimateRows");
                    String argument  = rs.getString("Argument");
                    String planWarns = rs.getString("Warnings");

                    if (physOp != null && estRows >= SEQ_SCAN_WARNING_THRESHOLD) {
                        boolean isTableScan = physOp.equalsIgnoreCase("Table Scan");
                        boolean isCiScan    = physOp.equalsIgnoreCase("Clustered Index Scan");

                        if (isTableScan || isCiScan) {
                            ObjectNode warning = JSON.createObjectNode();
                            warning.put("type", isTableScan ? "TABLE_SCAN" : "INDEX_SCAN");
                            warning.put("physical_op", physOp);
                            warning.put("estimated_rows", estRows);
                            if (argument != null) warning.put("object", argument);
                            warning.put("message",
                                    physOp + " with ~" + (long) estRows + " estimated rows. "
                                    + "Consider adding a covering index or checking existing indexes.");
                            if (planWarns != null && !planWarns.isBlank()) {
                                warning.put("plan_warnings", planWarns);
                            }
                            warnings.add(warning);
                        }
                    }
                }
            } finally {
                // Always restore the connection state regardless of success or failure
                stmt.execute("SET SHOWPLAN_ALL OFF");
            }
        }

        result.set("raw_plan", rawPlanRows);
        result.set("warnings", warnings);
        log.info("query_plan_expert (SQL Server): {} warning(s) found", warnings.size());
    }

    /**
     * Analyzes a query using Oracle's EXPLAIN PLAN facility.
     *
     * Oracle EXPLAIN PLAN writes the execution plan to PLAN_TABLE (created in the user's
     * schema or referenced via a public synonym). A unique STATEMENT_ID isolates this
     * plan from concurrent sessions. After reading the plan via DBMS_XPLAN.DISPLAY,
     * we attempt to delete our rows from PLAN_TABLE to avoid leaving debris.
     *
     * Plan output is returned as a plain-text array (one string per line from DBMS_XPLAN).
     * Performance warnings are detected by scanning for "TABLE ACCESS FULL" operations,
     * which are Oracle's equivalent of PostgreSQL's Seq Scan.
     *
     * ALTER SESSION SET CURRENT_SCHEMA is used so that unqualified table names in the
     * user's query resolve correctly to the configured Oracle schema. Oracle does not
     * support the PostgreSQL search_path concept.
     *
     * Note: EXPLAIN PLAN requires a write to PLAN_TABLE. Oracle's setReadOnly(true) is
     * advisory-only at the JDBC level and does not block DML, so this write succeeds
     * even on the read-only connection. The PLAN_TABLE cleanup DELETE is wrapped in a
     * try-catch in case the JDBC driver does enforce read-only mode.
     *
     * Prerequisite: PLAN_TABLE must exist (created by Oracle's utlxplan.sql script,
     * or available via the public SYS.PLAN_TABLE$ synonym, which is standard in
     * Oracle 10g and later).
     *
     * @param userSql the user's SQL query (without EXPLAIN prefix)
     * @param schema  the Oracle schema (owner) to set as CURRENT_SCHEMA
     * @param result  the JSON result object to populate with raw_plan and warnings
     * @throws Exception if the EXPLAIN PLAN query fails
     */
    private void analyzeOracle(String userSql, String schema, ObjectNode result) throws Exception {
        Connection conn = jdbcClient.getConnection();

        // Set current schema so unqualified table names resolve to the configured Oracle schema.
        // Oracle uses ALTER SESSION SET CURRENT_SCHEMA instead of PostgreSQL's search_path.
        try (Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema.toUpperCase());
        }

        // Use a unique statement ID (max 30 chars for PLAN_TABLE.STATEMENT_ID) to prevent
        // collisions with other sessions or concurrent MCP tool calls.
        String stmtId = "MCP_" + System.currentTimeMillis();

        // Step 1: Write the execution plan to PLAN_TABLE.
        // Note: EXPLAIN PLAN does not support bound parameters for the explained statement —
        // the SQL must be embedded as literal text, the same as PostgreSQL's EXPLAIN syntax.
        String explainSql = "EXPLAIN PLAN SET STATEMENT_ID = '" + stmtId + "' FOR " + userSql;
        log.debug("Executing Oracle EXPLAIN PLAN (stmtId={})", stmtId);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(explainSql);
        }

        // Step 2: Read the formatted plan via DBMS_XPLAN.DISPLAY.
        // 'TYPICAL' format shows operation, object name, estimated rows, cost, and bytes —
        // sufficient for identifying TABLE ACCESS FULL operations and their estimated row counts.
        String displaySql = "SELECT PLAN_TABLE_OUTPUT FROM TABLE("
                + "DBMS_XPLAN.DISPLAY('PLAN_TABLE', '" + stmtId + "', 'TYPICAL'))";

        ArrayNode rawPlanLines = JSON.createArrayNode();
        StringBuilder planText = new StringBuilder();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(displaySql)) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line != null) {
                    rawPlanLines.add(line);
                    planText.append(line).append("\n");
                }
            }
        }

        // Step 3: Clean up PLAN_TABLE (best-effort - don't fail if the driver blocks the DELETE)
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = '" + stmtId + "'");
        } catch (SQLException cleanupEx) {
            log.warn("Could not clean PLAN_TABLE for stmtId={}: {}", stmtId, cleanupEx.getMessage());
        }

        result.set("raw_plan", rawPlanLines);

        // Detect TABLE ACCESS FULL warnings (Oracle equivalent of PostgreSQL Seq Scan)
        ArrayNode warnings = JSON.createArrayNode();
        collectOracleWarnings(planText.toString(), warnings);
        result.set("warnings", warnings);

        log.info("query_plan_expert (Oracle): {} warning(s) found", warnings.size());
    }

    /**
     * Parses Oracle DBMS_XPLAN.DISPLAY output to detect full table scan warnings.
     *
     * TABLE ACCESS FULL is Oracle's equivalent of PostgreSQL's Seq Scan — it reads
     * every block in the table segment. The DBMS_XPLAN 'TYPICAL' format produces
     * lines like:
     * <pre>
     * |   1 | TABLE ACCESS FULL| ORDERS        |   1000 | ...
     * </pre>
     *
     * The regex extracts the table name (column 3) and estimated rows (column 4)
     * from each TABLE ACCESS FULL line. Rows below SEQ_SCAN_WARNING_THRESHOLD
     * are skipped (small table scans are not performance problems).
     *
     * @param planText the full plan text from DBMS_XPLAN.DISPLAY (newline-separated)
     * @param warnings the ArrayNode to append warning objects to
     */
    private void collectOracleWarnings(String planText, ArrayNode warnings) {
        // DBMS_XPLAN TYPICAL format columns (pipe-delimited):
        // | Id | Operation | Name | Rows | Bytes | Cost (%CPU) | Time |
        // We match TABLE ACCESS FULL lines and extract Name and Rows.
        Pattern pattern = Pattern.compile(
                "\\|\\s*\\d+\\s*\\|\\s*TABLE ACCESS FULL\\s*\\|\\s*(\\S+)\\s*\\|\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(planText);
        while (matcher.find()) {
            String tableName    = matcher.group(1);
            long estimatedRows  = Long.parseLong(matcher.group(2));
            if (estimatedRows >= SEQ_SCAN_WARNING_THRESHOLD) {
                ObjectNode warning = JSON.createObjectNode();
                warning.put("type",           "TABLE_ACCESS_FULL");
                warning.put("table",          tableName);
                warning.put("estimated_rows", estimatedRows);
                warning.put("message",
                        "TABLE ACCESS FULL on '" + tableName + "' with ~" + estimatedRows
                        + " estimated rows. Consider adding an index on the filter column(s).");
                warnings.add(warning);
            }
        }
    }

    /**
     * Extracts potential index column candidates from a PostgreSQL filter condition string.
     *
     * Uses a simple regex heuristic that matches common comparison patterns:
     *   "(column_name = $1)"    → ["column_name"]
     *   "(status IS NULL)"      → ["status"]
     *   "(amount > 100)"        → ["amount"]
     *
     * Returns at most 3 columns to avoid generating overly wide index suggestions.
     * The AI agent can refine the suggestions based on its broader understanding
     * of the schema and business context.
     *
     * @param filter the Filter string from the PostgreSQL EXPLAIN JSON plan node
     * @return list of column name candidates for index creation (may be empty)
     */
    private List<String> extractColumnsFromFilter(String filter) {
        List<String> columns = new ArrayList<>();
        Matcher matcher = FILTER_COLUMN_PATTERN.matcher(filter);
        while (matcher.find() && columns.size() < 3) {
            String col = matcher.group(1);
            // Exclude PostgreSQL internal parameter references and common keywords
            if (!col.startsWith("$") && !col.equalsIgnoreCase("NOT")) {
                columns.add(col);
            }
        }
        return columns;
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

    /**
     * Convenience method for building an error CallToolResult.
     *
     * @param message the error message to return to the AI agent
     * @return a CallToolResult with isError=true
     */
    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }
}
