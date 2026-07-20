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

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * MCP tool: apex_sql_runtime_stats
 *
 * Correlates an APEX application's SQL with its <em>real runtime cost</em> from the Oracle
 * shared pool (V$SQL). Where inspect_apex_performance and get_apex_source show the SQL a
 * component <em>contains</em>, this tool shows how expensive that SQL actually was to run:
 * executions, buffer gets (logical I/O), disk reads, rows processed, elapsed/CPU time, and
 * the plan hash — the empirical numbers that separate a real hotspot from a harmless query.
 *
 * How it correlates:
 *   APEX executes an application's SQL under the application's <em>parsing schema</em>
 *   (APEX_APPLICATIONS.OWNER). This tool looks up that schema and filters V$SQL by
 *   PARSING_SCHEMA_NAME, then ranks the statements by the chosen cost metric. An optional
 *   {@code sql_like} filter narrows the match to a specific query fragment (e.g. a table
 *   name lifted from a region's SQL) for a precise component-to-cost correlation.
 *
 * Limitations (important):
 *   - V$SQL is a live snapshot of the shared pool. A statement appears only if it is still
 *     cached — statements aged out, or never run since the last flush/restart, are absent.
 *     So run the pages of interest first, then query this tool.
 *   - Times in V$SQL are microseconds; this tool reports milliseconds.
 *   - Historical analysis (AWR/ASH) requires the Oracle Diagnostics Pack license and is
 *     intentionally NOT used here — this tool only reads the always-available V$SQL.
 *   - Needs SELECT on V$SQL. If the connected user lacks it, the result degrades gracefully
 *     to a privilege warning.
 *
 * Tool input parameters:
 *   - app_id         (required for stats) APEX application ID; its OWNER schema is the filter.
 *   - order_by       (optional, default "buffer_gets") ranking metric: buffer_gets | elapsed
 *                    | executions | cpu | disk_reads.
 *   - top_n          (optional, default 20) number of statements to return.
 *   - min_executions (optional, default 1) ignore statements executed fewer times than this.
 *   - sql_like       (optional) case-insensitive SQL_TEXT filter fragment (no % needed).
 *
 * Tool output (example, app_id=100):
 * <pre>
 * {
 *   "privilege_warnings": [],
 *   "applications": [ { "application_id": 100, "application_name": "HR Portal", "owner": "HR" } ],
 *   "parsing_schema": "HR",
 *   "order_by": "buffer_gets",
 *   "statements": [
 *     { "sql_id": "9fybzwvsqg2xb", "plan_hash_value": 2013457, "executions": 1450,
 *       "buffer_gets": 5800000, "buffer_gets_per_exec": 4000.0, "disk_reads": 12,
 *       "rows_processed": 1450, "elapsed_ms_total": 91000, "elapsed_ms_per_exec": 62.75,
 *       "cpu_ms_per_exec": 60.1, "last_active_time": "2026-07-20T09:41:03",
 *       "module": "APEX:APP 100:PAGE 12", "sql_text": "select ..." }
 *   ],
 *   "recommendations": [
 *     { "priority": "HIGH", "category": "HOT_SQL", "sql_id": "9fybzwvsqg2xb",
 *       "message": "sql_id 9fybzwvsqg2xb does 4000 buffer gets per execution over 1450 runs. "
 *                  + "Pass it to query_plan_expert (or DBMS_XPLAN by plan_hash_value 2013457)." }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage:
 *   - Feed a returned sql_id / plan_hash_value to query_plan_expert for the execution plan.
 *   - Use sql_like with a table or alias name from a region's SQL (via get_apex_source) to
 *     pin a specific component's runtime cost.
 */
public class ApexSqlRuntimeStats
        implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(ApexSqlRuntimeStats.class);

    public static final String TOOL_NAME = "apex_sql_runtime_stats";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Max characters of SQL text returned per statement (V$SQL.SQL_TEXT is up to ~1000 chars). */
    private static final int SQL_TEXT_MAX = 1000;

    /** buffer_gets per execution at/above this is a HIGH hot-SQL recommendation. */
    private static final long BUFGETS_HIGH = 100_000;
    /** buffer_gets per execution at/above this is a MEDIUM hot-SQL recommendation. */
    private static final long BUFGETS_MED  = 10_000;
    /** elapsed ms per execution at/above this is a HIGH hot-SQL recommendation. */
    private static final double ELAPSED_HIGH_MS = 1_000;
    /** elapsed ms per execution at/above this is a MEDIUM hot-SQL recommendation. */
    private static final double ELAPSED_MED_MS  = 250;

    /** Whitelist of order_by keys → V$SQL column (guards against SQL injection). */
    private static final Map<String, String> ORDER_COLUMNS = Map.of(
            "buffer_gets", "BUFFER_GETS",
            "elapsed",     "ELAPSED_TIME",
            "executions",  "EXECUTIONS",
            "cpu",         "CPU_TIME",
            "disk_reads",  "DISK_READS");

    private final JdbcClient jdbcClient;

    /**
     * Creates a new ApexSqlRuntimeStats tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected to an Oracle DB
     */
    public ApexSqlRuntimeStats(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for apex_sql_runtime_stats.
     *
     * @param jsonMapper the MCP SDK's JSON mapper for parsing the input schema string
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "app_id": {
                      "type": "integer",
                      "description": "APEX application ID. Its parsing schema (APEX_APPLICATIONS.OWNER) is used to filter V$SQL. If omitted, applications are listed but no stats are returned."
                    },
                    "order_by": {
                      "type": "string",
                      "description": "Ranking metric: buffer_gets (default), elapsed, executions, cpu, or disk_reads.",
                      "enum": ["buffer_gets", "elapsed", "executions", "cpu", "disk_reads"],
                      "default": "buffer_gets"
                    },
                    "top_n": {
                      "type": "integer",
                      "description": "Number of statements to return. Default: 20.",
                      "default": 20
                    },
                    "min_executions": {
                      "type": "integer",
                      "description": "Ignore statements executed fewer times than this. Default: 1.",
                      "default": 1
                    },
                    "sql_like": {
                      "type": "string",
                      "description": "Optional case-insensitive SQL_TEXT filter fragment (no % wildcards needed) to pin a specific query, e.g. a table name from a region's SQL."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Correlates an APEX application's SQL with its real runtime cost from \
                        V$SQL (executions, buffer gets, disk reads, rows, elapsed/CPU ms per \
                        execution, plan hash). Filters V$SQL by the app's parsing schema \
                        (APEX_APPLICATIONS.OWNER), ranks by a chosen metric, and can narrow \
                        to a query fragment via sql_like. Reads only the always-available \
                        V$SQL (no AWR/ASH, no Diagnostics Pack). A statement appears only \
                        while cached in the shared pool, so run the pages first. Oracle only.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the apex_sql_runtime_stats tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the analysis parameters
     * @return a CallToolResult containing the runtime statistics as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        String dbType = jdbcClient.getConfig().getDbType();
        if (!"oracle".equalsIgnoreCase(dbType)) {
            return errorResult("apex_sql_runtime_stats requires an Oracle database. "
                    + "Current db_type is '" + dbType + "'. Oracle APEX only runs on Oracle.");
        }

        Integer appId    = getIntArg(args, "app_id", null);
        String  orderBy  = getStrArg(args, "order_by", "buffer_gets");
        int     topN     = getIntArg(args, "top_n", 20);
        int     minExec  = getIntArg(args, "min_executions", 1);
        String  sqlLike  = getStrArg(args, "sql_like", null);

        String orderColumn = ORDER_COLUMNS.get(orderBy.toLowerCase());
        if (orderColumn == null) {
            orderColumn = "BUFFER_GETS";
            orderBy = "buffer_gets";
        }

        log.info("Tool '{}' called: app_id={}, order_by={}, top_n={}, min_executions={}, sql_like={}",
                TOOL_NAME, appId, orderBy, topN, minExec, sqlLike);

        try {
            Connection conn = jdbcClient.getConnection();
            List<String> warnings = new ArrayList<>();

            List<Map<String, Object>> apps = queryApplications(conn, appId, warnings);
            if (apps.isEmpty() && appId != null) {
                return errorResult("Application " + appId + " not found in APEX_APPLICATIONS. "
                        + "Verify the application ID and SELECT privilege on APEX_APPLICATIONS.");
            }

            String parsingSchema = null;
            List<Map<String, Object>> statements   = List.of();
            List<Map<String, Object>> recommendations = List.of();

            if (appId != null && !apps.isEmpty()) {
                parsingSchema = str(apps.get(0).get("owner"));
                if (parsingSchema.isBlank()) {
                    warnings.add("Application " + appId + " has no OWNER (parsing schema) in "
                            + "APEX_APPLICATIONS — cannot filter V$SQL.");
                } else {
                    statements = queryVSql(conn, parsingSchema, orderColumn, topN, minExec,
                            sqlLike, warnings);
                    if (statements.isEmpty() && warnings.isEmpty()) {
                        warnings.add("No cached statements for parsing schema '" + parsingSchema
                                + "' in V$SQL. The app's SQL is only visible while cached in the "
                                + "shared pool — run the relevant pages, then re-query. (Also check "
                                + "that the app actually parses under this schema.)");
                    }
                    recommendations = generateRecommendations(statements);
                }
            } else if (!apps.isEmpty()) {
                String appList = apps.stream()
                        .map(a -> a.get("application_id") + " (" + a.get("application_name") + ")")
                        .collect(Collectors.joining(", "));
                warnings.add("Set app_id to get runtime SQL stats. Available applications: " + appList);
            }

            ObjectNode result = JSON.createObjectNode();
            result.set("privilege_warnings", toStringArray(warnings));
            result.set("applications", toJsonArray(apps));
            if (parsingSchema != null) result.put("parsing_schema", parsingSchema);
            else                       result.putNull("parsing_schema");
            result.put("order_by", orderBy);
            result.set("statements", toJsonArray(statements));
            result.set("recommendations", toJsonArray(recommendations));

            log.info("apex_sql_runtime_stats: {} app(s), schema={}, {} statement(s), "
                    + "{} recommendation(s), {} warning(s)",
                    apps.size(), parsingSchema, statements.size(),
                    recommendations.size(), warnings.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("apex_sql_runtime_stats failed: {}", e.getMessage(), e);
            return errorResult("Error querying APEX SQL runtime stats: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /**
     * Queries APEX_APPLICATIONS for the list of applications (with OWNER = parsing schema).
     *
     * @param conn     active JDBC connection
     * @param appId    optional filter; null returns all applications
     * @param warnings mutable list for error messages
     * @return list of application records, empty if view is inaccessible or no matches
     */
    private List<Map<String, Object>> queryApplications(Connection conn, Integer appId,
            List<String> warnings) {
        String sql = "SELECT APPLICATION_ID, APPLICATION_NAME, OWNER, VERSION "
                   + "FROM APEX_APPLICATIONS "
                   + (appId != null ? "WHERE APPLICATION_ID = ? " : "")
                   + "ORDER BY APPLICATION_ID";
        Object[] params = appId != null ? new Object[]{appId} : new Object[0];
        return executeQuery(conn, sql, warnings, "APEX_APPLICATIONS", params);
    }

    /**
     * Queries V$SQL for the costliest cached statements parsed under the given schema.
     *
     * Per-execution metrics are computed in SQL (times converted microseconds → ms). The
     * order column is chosen from a whitelist, so it is safe to inline. The optional
     * sql_like filter is a bound parameter (case-insensitive, wrapped in %).
     *
     * @param conn          active JDBC connection
     * @param parsingSchema the app's parsing schema (V$SQL.PARSING_SCHEMA_NAME filter)
     * @param orderColumn   whitelisted V$SQL column to rank by (desc)
     * @param topN          maximum statements to return
     * @param minExec       minimum EXECUTIONS to include
     * @param sqlLike       optional case-insensitive SQL_TEXT fragment (null = no filter)
     * @param warnings      mutable list for error messages (e.g. missing V$SQL privilege)
     * @return statement stat records, ranked, empty on error / no cached SQL
     */
    private List<Map<String, Object>> queryVSql(Connection conn, String parsingSchema,
            String orderColumn, int topN, int minExec, String sqlLike, List<String> warnings) {
        boolean hasLike = sqlLike != null && !sqlLike.isBlank();
        String innerSql = "SELECT sql_id, plan_hash_value, executions, buffer_gets, disk_reads, "
                + "rows_processed, "
                + "ROUND(elapsed_time/1000) AS elapsed_ms_total, "
                + "ROUND(elapsed_time/GREATEST(executions,1)/1000, 2) AS elapsed_ms_per_exec, "
                + "ROUND(cpu_time/GREATEST(executions,1)/1000, 2) AS cpu_ms_per_exec, "
                + "ROUND(buffer_gets/GREATEST(executions,1), 1) AS buffer_gets_per_exec, "
                + "TO_CHAR(last_active_time, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS last_active_time, "
                + "module, "
                + "SUBSTR(sql_text, 1, " + SQL_TEXT_MAX + ") AS sql_text "
                + "FROM v$sql "
                + "WHERE parsing_schema_name = ? "
                + "AND executions >= ? "
                + (hasLike ? "AND UPPER(sql_text) LIKE UPPER(?) " : "")
                + "ORDER BY " + orderColumn + " DESC";
        String wrapped = "SELECT * FROM (" + innerSql + ") WHERE ROWNUM <= ?";

        List<Object> params = new ArrayList<>();
        params.add(parsingSchema);
        params.add(minExec);
        if (hasLike) params.add("%" + sqlLike + "%");
        params.add(topN);

        return executeQuery(conn, wrapped, warnings, "V$SQL", params.toArray());
    }

    // -------------------------------------------------------------------------
    // Recommendations engine
    // -------------------------------------------------------------------------

    /**
     * Flags statements whose per-execution cost is high enough to warrant a plan review.
     *
     * A HOT_SQL recommendation is produced when buffer_gets_per_exec or elapsed_ms_per_exec
     * crosses a threshold: HIGH at {@link #BUFGETS_HIGH}/{@link #ELAPSED_HIGH_MS}, MEDIUM at
     * {@link #BUFGETS_MED}/{@link #ELAPSED_MED_MS}. The message routes the agent to
     * query_plan_expert with the sql_id / plan_hash_value.
     *
     * @param statements ranked statement records from {@link #queryVSql}
     * @return recommendations sorted HIGH → MEDIUM
     */
    private List<Map<String, Object>> generateRecommendations(List<Map<String, Object>> statements) {
        List<Map<String, Object>> recs = new ArrayList<>();
        for (Map<String, Object> s : statements) {
            long   bgPerExec = s.get("buffer_gets_per_exec") instanceof Number n ? n.longValue() : 0;
            double msPerExec = s.get("elapsed_ms_per_exec")  instanceof Number n ? n.doubleValue() : 0;

            String priority = null;
            if (bgPerExec >= BUFGETS_HIGH || msPerExec >= ELAPSED_HIGH_MS)      priority = "HIGH";
            else if (bgPerExec >= BUFGETS_MED || msPerExec >= ELAPSED_MED_MS)   priority = "MEDIUM";
            if (priority == null) continue;

            Object sqlId = s.get("sql_id");
            Object planHash = s.get("plan_hash_value");
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",             priority);
            rec.put("category",             "HOT_SQL");
            rec.put("sql_id",               sqlId);
            rec.put("plan_hash_value",      planHash);
            rec.put("buffer_gets_per_exec", s.get("buffer_gets_per_exec"));
            rec.put("elapsed_ms_per_exec",  s.get("elapsed_ms_per_exec"));
            rec.put("executions",           s.get("executions"));
            rec.put("message", "sql_id " + sqlId + " averages " + bgPerExec + " buffer gets and "
                    + msPerExec + "ms per execution over " + s.get("executions") + " runs. "
                    + "Pass it to query_plan_expert (or DBMS_XPLAN.DISPLAY_CURSOR by sql_id, or by "
                    + "plan_hash_value " + planHash + ") to inspect the execution plan and look for "
                    + "full scans or missing indexes.");
            recs.add(rec);
        }
        recs.sort(Comparator.comparingInt(r -> "HIGH".equals(r.get("priority")) ? 0 : 1));
        return recs;
    }

    // -------------------------------------------------------------------------
    // Generic query executor
    // -------------------------------------------------------------------------

    /**
     * Runs a parameterized SQL query and returns results as a list of ordered maps.
     *
     * Column names are lowercased for idiomatic JSON keys. CLOB values are materialized to
     * String; temporal types become ISO-8601 strings. Failures (ORA-00942 missing privilege
     * on V$SQL) are recorded in warnings and return an empty list.
     *
     * @param conn     active JDBC connection
     * @param sql      parameterized SQL with ? placeholders
     * @param warnings mutable list for error messages
     * @param viewName human-readable name for error messages
     * @param params   bind parameters in positional order
     * @return list of row maps (lowercased column → value), empty on error
     */
    private List<Map<String, Object>> executeQuery(Connection conn, String sql,
            List<String> warnings, String viewName, Object[] params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if      (params[i] instanceof Integer v)    ps.setInt(i + 1, v);
                else if (params[i] instanceof Long v)       ps.setLong(i + 1, v);
                else if (params[i] instanceof String v)     ps.setString(i + 1, v);
                else if (params[i] instanceof BigDecimal v) ps.setBigDecimal(i + 1, v);
                else                                        ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    cols.add(meta.getColumnLabel(i).toLowerCase());
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        if (val instanceof Clob clob) {
                            long len = clob.length();
                            row.put(cols.get(i - 1), len == 0 ? "" : clob.getSubString(1, (int) len));
                        } else if (val instanceof Timestamp ts) {
                            row.put(cols.get(i - 1), ts.toLocalDateTime().toString());
                        } else if (val instanceof java.sql.Date d) {
                            row.put(cols.get(i - 1), d.toLocalDate().toString());
                        } else {
                            row.put(cols.get(i - 1), val);
                        }
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            warnings.add(viewName + " query failed: " + firstLine(e.getMessage())
                    + (viewName.equals("V$SQL")
                        ? " — the connected user needs SELECT on V$SQL (or SELECT_CATALOG_ROLE)."
                        : ""));
            log.warn("Query on {} failed: {}", viewName, e.getMessage());
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a list of row maps to a Jackson ArrayNode.
     *
     * @param rows list of row maps
     * @return an ArrayNode ready for JSON serialization
     */
    private ArrayNode toJsonArray(List<Map<String, Object>> rows) {
        ArrayNode array = JSON.createArrayNode();
        for (Map<String, Object> row : rows) {
            ObjectNode node = JSON.createObjectNode();
            row.forEach((k, v) -> putValue(node, k, v));
            array.add(node);
        }
        return array;
    }

    /**
     * Converts a list of strings to a Jackson ArrayNode.
     *
     * @param strings list of strings
     * @return an ArrayNode of JSON string elements
     */
    private ArrayNode toStringArray(List<String> strings) {
        ArrayNode array = JSON.createArrayNode();
        strings.forEach(array::add);
        return array;
    }

    /**
     * Puts a value into a Jackson ObjectNode using the correct typed put overload.
     *
     * @param node  the target ObjectNode
     * @param key   the field name
     * @param value null, String, Long, Integer, BigDecimal, Double, Float, Boolean, or Object
     */
    private void putValue(ObjectNode node, String key, Object value) {
        if      (value == null)                 node.putNull(key);
        else if (value instanceof String s)     node.put(key, s);
        else if (value instanceof Long l)       node.put(key, l);
        else if (value instanceof Integer i)    node.put(key, i);
        else if (value instanceof BigDecimal b) node.put(key, b);
        else if (value instanceof Double d)     node.put(key, d);
        else if (value instanceof Float f)      node.put(key, f);
        else if (value instanceof Boolean b)    node.put(key, b);
        else if (value instanceof Number n)     node.put(key, n.doubleValue());
        else                                    node.put(key, value.toString());
    }

    // -------------------------------------------------------------------------
    // Argument / utility helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts an integer argument from the tool call arguments map.
     *
     * @param args         tool call arguments map
     * @param key          argument name
     * @param defaultValue fallback when key is absent or null
     * @return resolved integer, or defaultValue
     */
    private Integer getIntArg(Map<String, Object> args, String key, Integer defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Extracts a string argument from the tool call arguments map.
     *
     * @param args         tool call arguments map
     * @param key          argument name
     * @param defaultValue fallback when key is absent or null/blank
     * @return resolved string, or defaultValue
     */
    private String getStrArg(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        String s = val.toString();
        return s.isBlank() ? defaultValue : s;
    }

    /**
     * Null-safe String coercion.
     *
     * @param o any object (may be null)
     * @return the object's toString, or "" if null
     */
    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * Returns the first line of an exception message to keep warnings concise.
     *
     * @param message full exception message (may be null)
     * @return first line, or empty string if message is null
     */
    private String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl > 0 ? message.substring(0, nl) : message;
    }

    /**
     * Convenience method for building an error CallToolResult.
     *
     * @param message error message to return to the AI agent
     * @return a CallToolResult with isError=true
     */
    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }
}
