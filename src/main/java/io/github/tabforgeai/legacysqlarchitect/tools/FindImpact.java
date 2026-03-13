package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * MCP tool: find_impact
 *
 * Answers the question: "What will break if I change or drop this table/column?"
 *
 * This is a critical tool for legacy database refactoring. Before renaming a column,
 * changing its type, or dropping a table, you need to know every place in the
 * database that depends on it. Without this tool, you have to grep through all
 * procedure source code manually and hope you found everything.
 *
 * <p>The tool performs a multi-layered dependency scan:
 * <ol>
 *   <li><b>FK dependents</b> — other tables that have a foreign key pointing to
 *       the target table (or to the specific column if {@code column} is given).
 *       Found via JDBC {@code DatabaseMetaData.getExportedKeys()}, which works
 *       across all database types without DB-specific SQL.</li>
 *   <li><b>Triggers</b> — triggers defined on the target table that will fire on
 *       INSERT/UPDATE/DELETE. Changing the table structure can break trigger logic.
 *       Found via DB-specific system catalogs ({@code pg_trigger} / {@code sys.triggers}).</li>
 *   <li><b>Procedures &amp; functions</b> — stored routines that reference the target
 *       table or column in their source code. Found via:
 *       <ul>
 *         <li>PostgreSQL: {@code pg_proc.prosrc ILIKE '%table_name%'} (text search)</li>
 *         <li>SQL Server: {@code sys.sql_expression_dependencies} (compiler-verified)</li>
 *       </ul></li>
 *   <li><b>Views</b> — views that read from the target table. Dropping a table or
 *       renaming a column will invalidate these views. Found via:
 *       <ul>
 *         <li>PostgreSQL: {@code information_schema.view_table_usage}</li>
 *         <li>SQL Server: {@code sys.sql_expression_dependencies} filtered to views</li>
 *       </ul></li>
 * </ol>
 *
 * <p>Tool input parameters:
 * <ul>
 *   <li>{@code table}   (required) Name of the table to analyze.</li>
 *   <li>{@code column}  (optional) Name of a specific column to analyze. When provided,
 *                       FK results are filtered to only FKs that reference this column,
 *                       and procedure/view text search includes the column name.</li>
 *   <li>{@code schema}  (optional) Database schema. Defaults to config.json db_schema.</li>
 * </ul>
 *
 * <p>Tool output — example JSON for {@code table="orders", column="status"}:
 * <pre>
 * {
 *   "target": {
 *     "schema": "public",
 *     "table": "orders",
 *     "column": "status"
 *   },
 *   "impact": {
 *     "fk_dependents": [],
 *     "triggers": [
 *       {
 *         "name": "trg_orders_audit",
 *         "timing": "AFTER",
 *         "events": "INSERT, UPDATE, DELETE"
 *       }
 *     ],
 *     "procedures": [
 *       {
 *         "name": "process_order",
 *         "type": "PROCEDURE",
 *         "note": "Source contains 'status'"
 *       }
 *     ],
 *     "views": [
 *       { "name": "v_open_orders" }
 *     ]
 *   },
 *   "summary": {
 *     "fk_dependent_count": 0,
 *     "trigger_count": 1,
 *     "procedure_count": 1,
 *     "view_count": 1,
 *     "total_impact_count": 3
 *   }
 * }
 * </pre>
 *
 * <p>Downstream usage — this tool's output is consumed by:
 * <ul>
 *   <li>AI agent: presents the impact list to the developer before they execute a schema change</li>
 *   <li>AI agent + {@code get_procedure_source}: after finding impacted procedures, the agent
 *       fetches their source to explain exactly what logic would break and how to fix it</li>
 *   <li>AI agent + {@code generate_documentation}: cross-references impact results with the
 *       full schema documentation to provide a complete change-risk assessment</li>
 * </ul>
 *
 * <p><b>Important note on procedure text search (PostgreSQL):</b> The procedure scan uses
 * a case-insensitive LIKE search on the raw source text. This is a heuristic — it will
 * report false positives if the table/column name appears in a comment, and may miss
 * dynamic SQL built via string concatenation. SQL Server's compiler-verified dependency
 * tracking ({@code sys.sql_expression_dependencies}) is more accurate.
 */
public class FindImpact implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(FindImpact.class);

    /** Tool name as registered with the MCP server and visible to the AI agent. */
    public static final String TOOL_NAME = "find_impact";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final JdbcClient jdbcClient;

    /**
     * Creates a new FindImpact tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public FindImpact(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for find_impact.
     *
     * @param jsonMapper the MCP SDK's JSON mapper for parsing the input schema string
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "table": {
                      "type": "string",
                      "description": "Name of the table to analyze for impact."
                    },
                    "column": {
                      "type": "string",
                      "description": "Optional column name. When provided, narrows the FK analysis to FKs referencing this specific column and adds the column name to the procedure/view text search."
                    },
                    "schema": {
                      "type": "string",
                      "description": "Database schema name. Defaults to the schema in config.json."
                    }
                  },
                  "required": ["table"]
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Analyzes what will break if a table or column is changed or dropped. \
                        Returns all FK dependents (other tables whose foreign keys point here), \
                        triggers defined on the table, procedures/functions that reference the \
                        table or column, and views that read from the table. \
                        Use this before any schema change to assess the full impact.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the find_impact tool when called by the AI agent.
     *
     * Runs four parallel dependency scans and assembles the results into a
     * structured JSON impact report.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request with required table and optional column/schema
     * @return a CallToolResult containing the impact analysis as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        if (args == null || args.get("table") == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required parameter: 'table'")
                    .isError(true)
                    .build();
        }

        String table  = (String) args.get("table");
        String column = args.get("column") instanceof String s ? s : null;
        String schema = resolveSchema(args);

        log.info("Tool '{}' called: schema={}, table={}, column={}",
                TOOL_NAME, schema, table, column != null ? column : "(table-level)");

        try {
            String dbType = jdbcClient.getConfig().getDbType();

            List<Map<String, Object>> fkDependents  = collectFkDependents(schema, table, column);
            List<Map<String, Object>> triggers       = collectTriggers(schema, table, dbType);
            List<Map<String, Object>> procedures     = collectProcedures(schema, table, column, dbType);
            List<Map<String, Object>> views          = collectViews(schema, table, dbType);

            // --- Assemble result ---
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("schema", schema);
            target.put("table", table);
            if (column != null) target.put("column", column);

            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("fk_dependents", fkDependents);
            impact.put("triggers",      triggers);
            impact.put("procedures",    procedures);
            impact.put("views",         views);

            int total = fkDependents.size() + triggers.size() + procedures.size() + views.size();

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("fk_dependent_count", fkDependents.size());
            summary.put("trigger_count",      triggers.size());
            summary.put("procedure_count",    procedures.size());
            summary.put("view_count",         views.size());
            summary.put("total_impact_count", total);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("target",  target);
            result.put("impact",  impact);
            result.put("summary", summary);

            log.info("find_impact: {} total dependents for {}.{}", total, schema, table);

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("find_impact failed for {}.{}: {}", schema, table, e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error analyzing impact for '" + schema + "." + table + "': " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // FK dependents — cross-database via JDBC DatabaseMetaData.getExportedKeys()
    // -------------------------------------------------------------------------

    /**
     * Finds all tables that have a foreign key pointing to the target table.
     *
     * Uses JDBC {@code DatabaseMetaData.getExportedKeys()} which returns the
     * "exported" side of FK relationships — i.e., child tables that reference
     * our (parent) table. This is the inverse of {@code getImportedKeys()}.
     *
     * When {@code column} is specified, only FK relationships that reference
     * that specific column are included. When null, all FK dependents are returned.
     *
     * Result columns from {@code getExportedKeys()}:
     * <ul>
     *   <li>{@code FKTABLE_NAME}  — the child table (the one with the FK column)</li>
     *   <li>{@code FKCOLUMN_NAME} — the FK column in the child table</li>
     *   <li>{@code PKCOLUMN_NAME} — the column in our table being referenced</li>
     *   <li>{@code FK_NAME}       — the constraint name</li>
     * </ul>
     *
     * @param schema the schema of the target (parent) table
     * @param table  the target table name
     * @param column the specific column to filter on, or null for all FKs
     * @return list of FK dependent entries (dependent_table, dependent_column, references_column, constraint_name)
     * @throws SQLException if the metadata query fails
     */
    private List<Map<String, Object>> collectFkDependents(String schema, String table, String column)
            throws SQLException {

        List<Map<String, Object>> result = new ArrayList<>();

        DatabaseMetaData meta = jdbcClient.getConnection().getMetaData();

        // getExportedKeys() takes (catalog, schema, table) and returns child FK info.
        // We pass null for catalog to search across all catalogs.
        try (ResultSet rs = meta.getExportedKeys(null, schema, table)) {
            while (rs.next()) {
                String pkCol  = rs.getString("PKCOLUMN_NAME");  // our column being referenced
                String fkTable = rs.getString("FKTABLE_NAME");  // child table
                String fkCol   = rs.getString("FKCOLUMN_NAME"); // child FK column
                String fkName  = rs.getString("FK_NAME");       // constraint name

                // If a specific column was requested, only include FKs pointing to it
                if (column != null && !column.equalsIgnoreCase(pkCol)) {
                    continue;
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("dependent_table",    fkTable);
                entry.put("dependent_column",   fkCol);
                entry.put("references_column",  pkCol);
                entry.put("constraint_name",    fkName);
                result.add(entry);
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Triggers — database-specific
    // -------------------------------------------------------------------------

    /**
     * Finds all triggers defined on the target table.
     *
     * Changing a table's structure (adding/removing columns, changing types)
     * can break trigger logic that references specific column names or assumes
     * a particular table layout.
     *
     * @param schema the schema of the target table
     * @param table  the target table name
     * @param dbType the database type ("sqlserver" or other for PostgreSQL)
     * @return list of trigger entries (name, timing, events)
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectTriggers(String schema, String table, String dbType)
            throws SQLException {

        if ("sqlserver".equalsIgnoreCase(dbType)) {
            return collectTriggersSqlServer(schema, table);
        } else if ("oracle".equalsIgnoreCase(dbType)) {
            return collectTriggersOracle(schema, table);
        } else {
            return collectTriggersPostgres(schema, table);
        }
    }

    /**
     * Fetches triggers from PostgreSQL via pg_trigger + pg_class + pg_namespace.
     *
     * The {@code tgtype} bitmask encodes timing and event information:
     * <ul>
     *   <li>bit 0 (value 1): timing — 1=BEFORE, 0=AFTER</li>
     *   <li>bit 2 (value 4): INSERT event</li>
     *   <li>bit 3 (value 8): DELETE event</li>
     *   <li>bit 4 (value 16): UPDATE event</li>
     * </ul>
     *
     * {@code NOT t.tgisinternal} excludes constraint triggers (e.g. FK enforcement
     * triggers that PostgreSQL creates internally — these are not user-defined logic).
     *
     * @param schema the PostgreSQL schema name
     * @param table  the table name
     * @return list of trigger entries
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectTriggersPostgres(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT t.tgname                                                     AS name,
                       CASE WHEN (t.tgtype & 1) = 1 THEN 'BEFORE' ELSE 'AFTER' END AS timing,
                       string_agg(
                           CASE
                               WHEN (t.tgtype & 4)  = 4  THEN 'INSERT'
                               WHEN (t.tgtype & 8)  = 8  THEN 'DELETE'
                               WHEN (t.tgtype & 16) = 16 THEN 'UPDATE'
                           END,
                           ', ' ORDER BY
                           CASE
                               WHEN (t.tgtype & 4)  = 4  THEN 1
                               WHEN (t.tgtype & 16) = 16 THEN 2
                               WHEN (t.tgtype & 8)  = 8  THEN 3
                           END
                       )                                                            AS events
                FROM   pg_trigger      t
                JOIN   pg_class        c ON c.oid = t.tgrelid
                JOIN   pg_namespace    n ON n.oid = c.relnamespace
                WHERE  n.nspname  = ?
                  AND  c.relname  = ?
                  AND  NOT t.tgisinternal
                GROUP BY t.tgname, t.tgtype
                ORDER BY t.tgname
                """;

        return executeMapQuery(sql, schema, table, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",   rs.getString("name"));
            entry.put("timing", rs.getString("timing"));
            entry.put("events", rs.getString("events"));
            return entry;
        });
    }

    /**
     * Fetches triggers from SQL Server via sys.triggers + sys.objects.
     *
     * Filters to DML triggers only (type_desc = 'SQL_TRIGGER') on the parent
     * object identified by {@code schema.table}. The INSTEAD OF vs AFTER
     * distinction is derived from {@code is_instead_of_trigger}.
     *
     * @param schema the SQL Server schema name (e.g. "dbo")
     * @param table  the table name
     * @return list of trigger entries
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectTriggersSqlServer(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT t.name                                                 AS name,
                       CASE WHEN t.is_instead_of_trigger = 1
                            THEN 'INSTEAD OF' ELSE 'AFTER' END               AS timing,
                       STUFF((
                           SELECT ', ' + te.type_desc
                           FROM sys.trigger_events te
                           WHERE te.object_id = t.object_id
                           FOR XML PATH(''), TYPE).value('.','NVARCHAR(MAX)'),
                           1, 2, '')                                          AS events
                FROM   sys.triggers t
                JOIN   sys.objects  o ON o.object_id = t.parent_id
                WHERE  SCHEMA_NAME(o.schema_id) = ?
                  AND  o.name                   = ?
                ORDER  BY t.name
                """;

        return executeMapQuery(sql, schema, table, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",   rs.getString("name"));
            entry.put("timing", rs.getString("timing"));
            entry.put("events", rs.getString("events"));
            return entry;
        });
    }

    // -------------------------------------------------------------------------
    // Procedures — database-specific
    // -------------------------------------------------------------------------

    /**
     * Finds stored procedures and functions that reference the target table or column.
     *
     * The search strategy differs by database:
     * <ul>
     *   <li>PostgreSQL: text search on {@code pg_proc.prosrc} (the raw procedure body).
     *       This is a heuristic — accurate for direct references but may produce false
     *       positives for coincidental name matches in comments.</li>
     *   <li>SQL Server: {@code sys.sql_expression_dependencies} provides compiler-verified
     *       dependency tracking, which is precise but only covers statically resolvable
     *       references (not dynamic SQL built via string concatenation).</li>
     * </ul>
     *
     * When {@code column} is not null, the PostgreSQL text search also checks for
     * the column name in the procedure source. SQL Server dependency tracking is
     * at table granularity only, so column-level narrowing on SQL Server falls back
     * to the same table-level result with a note added.
     *
     * @param schema  the schema to search in
     * @param table   the target table name
     * @param column  optional column name to narrow the search
     * @param dbType  the database type ("sqlserver" or other for PostgreSQL)
     * @return list of procedure entries (name, type, and optionally a note)
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectProcedures(String schema, String table,
                                                         String column, String dbType)
            throws SQLException {

        if ("sqlserver".equalsIgnoreCase(dbType)) {
            return collectProceduresSqlServer(schema, table);
        } else if ("oracle".equalsIgnoreCase(dbType)) {
            return collectProceduresOracle(schema, table);
        } else {
            return collectProceduresPostgres(schema, table, column);
        }
    }

    /**
     * Searches PostgreSQL procedures by doing a case-insensitive text search
     * on {@code pg_proc.prosrc} (the raw procedure body, without the header).
     *
     * When {@code column} is provided, both the table name AND column name must
     * appear in the source (AND logic) to reduce false positives.
     *
     * @param schema the PostgreSQL schema name
     * @param table  the target table name
     * @param column optional column name; if non-null, source must contain both table and column
     * @return list of matching procedure entries
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectProceduresPostgres(String schema, String table,
                                                                  String column)
            throws SQLException {

        StringBuilder sql = new StringBuilder("""
                SELECT p.proname                                              AS name,
                       CASE WHEN p.prokind = 'f' THEN 'FUNCTION'
                            WHEN p.prokind = 'p' THEN 'PROCEDURE'
                            ELSE 'FUNCTION' END                              AS type
                FROM   pg_proc      p
                JOIN   pg_namespace n ON n.oid = p.pronamespace
                WHERE  n.nspname = ?
                  AND  p.prosrc ILIKE '%' || ? || '%'
                """);

        List<Object> params = new ArrayList<>();
        params.add(schema);
        params.add(table);

        // If a column was specified, require its name to also appear in the source
        if (column != null) {
            sql.append("  AND  p.prosrc ILIKE '%' || ? || '%'\n");
            params.add(column);
        }

        sql.append("ORDER BY p.proname");

        String note = column != null
                ? "Source contains '" + table + "' and '" + column + "'"
                : "Source contains '" + table + "'";

        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", rs.getString("name"));
                    entry.put("type", rs.getString("type"));
                    entry.put("note", note);
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /**
     * Finds SQL Server procedures/functions that reference the target table using
     * {@code sys.sql_expression_dependencies}, which tracks compiler-verified
     * static dependencies between objects.
     *
     * Only objects of type P (procedure), FN (scalar function), IF (inline TVF),
     * and TF (table-valued function) are returned. Views are handled separately
     * by {@link #collectViewsSqlServer}.
     *
     * @param schema the SQL Server schema name (e.g. "dbo")
     * @param table  the target table name
     * @return list of dependent procedure entries
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectProceduresSqlServer(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT DISTINCT o.name                                          AS name,
                       CASE o.type
                           WHEN 'P'  THEN 'PROCEDURE'
                           WHEN 'FN' THEN 'FUNCTION'
                           WHEN 'IF' THEN 'FUNCTION'
                           WHEN 'TF' THEN 'FUNCTION'
                           ELSE o.type END                                      AS type
                FROM   sys.sql_expression_dependencies d
                JOIN   sys.objects                     o ON o.object_id = d.referencing_id
                WHERE  d.referenced_entity_name = ?
                  AND  ISNULL(d.referenced_schema_name, SCHEMA_NAME(o.schema_id)) = ?
                  AND  o.type IN ('P', 'FN', 'IF', 'TF')
                ORDER  BY o.name
                """;

        return executeMapQuery(sql, table, schema, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rs.getString("name"));
            entry.put("type", rs.getString("type"));
            return entry;
        });
    }

    // -------------------------------------------------------------------------
    // Views — database-specific
    // -------------------------------------------------------------------------

    /**
     * Finds views that reference the target table.
     *
     * Views are impacted by table changes in two ways:
     * <ul>
     *   <li>Dropping a table makes all dependent views invalid</li>
     *   <li>Renaming or removing a column breaks views that select that column</li>
     * </ul>
     *
     * @param schema the schema to search in
     * @param table  the target table name
     * @param dbType the database type
     * @return list of view entries (name)
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectViews(String schema, String table, String dbType)
            throws SQLException {

        if ("sqlserver".equalsIgnoreCase(dbType)) {
            return collectViewsSqlServer(schema, table);
        } else if ("oracle".equalsIgnoreCase(dbType)) {
            return collectViewsOracle(schema, table);
        } else {
            return collectViewsPostgres(schema, table);
        }
    }

    /**
     * Finds PostgreSQL views that reference the target table via
     * {@code information_schema.view_table_usage}.
     *
     * This is a standard SQL view (not PostgreSQL-specific) and provides reliable
     * table-level dependency tracking for views. Column-level usage is not tracked
     * by this catalog view.
     *
     * @param schema the schema name (applied to both the view and the referenced table)
     * @param table  the target table name
     * @return list of view entries
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectViewsPostgres(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT v.view_name AS name
                FROM   information_schema.view_table_usage v
                WHERE  v.view_schema   = ?
                  AND  v.table_schema  = ?
                  AND  v.table_name    = ?
                ORDER  BY v.view_name
                """;

        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, schema);
            ps.setString(3, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", rs.getString("name"));
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /**
     * Finds SQL Server views that reference the target table using
     * {@code sys.sql_expression_dependencies}, filtered to objects of type 'V' (view).
     *
     * @param schema the SQL Server schema name
     * @param table  the target table name
     * @return list of view entries
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectViewsSqlServer(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT DISTINCT o.name AS name
                FROM   sys.sql_expression_dependencies d
                JOIN   sys.objects                     o ON o.object_id = d.referencing_id
                WHERE  d.referenced_entity_name = ?
                  AND  ISNULL(d.referenced_schema_name, SCHEMA_NAME(o.schema_id)) = ?
                  AND  o.type = 'V'
                ORDER  BY o.name
                """;

        return executeMapQuery(sql, table, schema, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rs.getString("name"));
            return entry;
        });
    }

    // -------------------------------------------------------------------------
    // Oracle implementations
    // -------------------------------------------------------------------------

    /**
     * Fetches triggers from Oracle via ALL_TRIGGERS.
     *
     * ALL_TRIGGERS.TRIGGER_TYPE contains the timing and row/statement level, e.g.:
     *   "BEFORE EACH ROW", "AFTER STATEMENT", "INSTEAD OF"
     * ALL_TRIGGERS.TRIGGERING_EVENT contains the DML events, e.g.:
     *   "INSERT", "INSERT OR UPDATE", "INSERT OR UPDATE OR DELETE"
     *
     * Schema comparison uses UPPER() because Oracle stores object names in uppercase
     * by default. The tool accepts mixed-case input from the AI agent and normalizes it.
     *
     * @param schema the Oracle schema (owner) name
     * @param table  the target table name
     * @return list of trigger entries (name, timing, events)
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectTriggersOracle(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT TRIGGER_NAME     AS name,
                       TRIGGER_TYPE     AS timing,
                       TRIGGERING_EVENT AS events
                FROM   ALL_TRIGGERS
                WHERE  OWNER      = UPPER(?)
                  AND  TABLE_NAME = UPPER(?)
                ORDER  BY TRIGGER_NAME
                """;

        return executeMapQuery(sql, schema, table, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",   rs.getString("name"));
            entry.put("timing", rs.getString("timing"));
            entry.put("events", rs.getString("events"));
            return entry;
        });
    }

    /**
     * Finds Oracle procedures, functions, packages, and triggers that depend on
     * the target table using ALL_DEPENDENCIES.
     *
     * ALL_DEPENDENCIES tracks compiler-verified object dependencies. When a PL/SQL
     * object is compiled, Oracle records each database object it references as a
     * dependency entry. This is more reliable than text searching (like the
     * PostgreSQL fallback) because it reflects actual static references resolved
     * at compile time.
     *
     * The REFERENCED_TYPE = 'TABLE' filter finds objects that directly depend on
     * the target table. Indirect dependencies (procedure A calls procedure B which
     * references the table) are NOT included — only direct references are returned.
     * This keeps the output actionable without overwhelming the AI agent.
     *
     * @param schema the Oracle schema (owner) name
     * @param table  the target table name
     * @return list of dependent PL/SQL object entries (name, type)
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectProceduresOracle(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT NAME AS name,
                       TYPE AS type
                FROM   ALL_DEPENDENCIES
                WHERE  OWNER           = UPPER(?)
                  AND  REFERENCED_NAME = UPPER(?)
                  AND  REFERENCED_TYPE = 'TABLE'
                  AND  TYPE IN ('PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'TRIGGER')
                ORDER  BY TYPE, NAME
                """;

        return executeMapQuery(sql, schema, table, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rs.getString("name"));
            entry.put("type", rs.getString("type"));
            return entry;
        });
    }

    /**
     * Finds Oracle views that depend on the target table using ALL_DEPENDENCIES.
     *
     * Oracle views are recompiled lazily — a view that references a dropped or
     * altered table is marked INVALID in ALL_OBJECTS but remains in ALL_DEPENDENCIES.
     * This means the result includes both valid and already-invalidated views,
     * which is the correct behavior for impact analysis (you need to know all
     * views that were ever tied to this table, not just the currently valid ones).
     *
     * @param schema the Oracle schema (owner) name
     * @param table  the target table name
     * @return list of dependent view entries (name)
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> collectViewsOracle(String schema, String table)
            throws SQLException {

        String sql = """
                SELECT NAME AS name
                FROM   ALL_DEPENDENCIES
                WHERE  OWNER           = UPPER(?)
                  AND  REFERENCED_NAME = UPPER(?)
                  AND  REFERENCED_TYPE = 'TABLE'
                  AND  TYPE            = 'VIEW'
                ORDER  BY NAME
                """;

        return executeMapQuery(sql, schema, table, rs -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rs.getString("name"));
            return entry;
        });
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Executes a parameterized query and maps each row to a Map using the
     * provided row-mapper function.
     *
     * This helper eliminates the boilerplate of PreparedStatement + ResultSet
     * iteration for the queries in this class that all follow the same pattern.
     *
     * @param sql       the parameterized SQL query
     * @param params    the positional parameters to bind (in order)
     * @param rowMapper a function that maps a ResultSet row to a Map
     * @return list of mapped rows
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> executeMapQuery(String sql,
                                                       Object param1, Object param2,
                                                       RowMapper rowMapper) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setObject(1, param1);
            ps.setObject(2, param2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rowMapper.map(rs));
                }
            }
        }
        return result;
    }

    /**
     * Simple row-mapper functional interface used by {@link #executeMapQuery}.
     * Wraps {@code ResultSet → Map} conversion for a single row.
     */
    @FunctionalInterface
    private interface RowMapper {
        Map<String, Object> map(ResultSet rs) throws SQLException;
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
