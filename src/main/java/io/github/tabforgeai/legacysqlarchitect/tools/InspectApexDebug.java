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
 * MCP tool: inspect_apex_debug
 *
 * Reads Oracle APEX's own debug log — the most granular, empirical "where did the page
 * spend its time" data that APEX produces. Where inspect_apex_performance reasons
 * statically about what <em>could</em> be slow, this tool reports what <em>actually</em>
 * happened during real, debug-captured page renders.
 *
 * How APEX debug works:
 *   When a developer (or end user with a debug-enabled session) runs a page with debug
 *   turned on, APEX records a timed trace of the entire request: every region render,
 *   process, computation, validation, and internal step, each with its own execution
 *   time and a running elapsed time from the start of the request. This trace is exposed
 *   through a single dictionary view:
 *
 *   - APEX_DEBUG_MESSAGES — the individual trace steps, each with EXECUTION_TIME (step
 *     duration) and ELAPSED_TIME (cumulative since request start), grouped by PAGE_VIEW_ID.
 *
 *   There is no separate page-view header view on APEX 26; this tool reconstructs the
 *   "page views" by aggregating APEX_DEBUG_MESSAGES on PAGE_VIEW_ID (total elapsed time =
 *   MAX(ELAPSED_TIME) within the group).
 *
 *   APEX stores these times in <em>seconds</em> (fractional). This tool converts them to
 *   milliseconds for readability ("elapsed_ms", "execution_ms").
 *
 * Two-phase workflow (the tool does phase 1 automatically and, when possible, phase 2):
 *   1. List the slowest captured page views for the application (ELAPSED_TIME desc).
 *   2. Drill into one page view's trace and surface the slowest individual steps
 *      (EXECUTION_TIME desc) — this is the line that tells you exactly which region or
 *      process consumed the time.
 *
 *   If no page_view_id is supplied, the tool auto-drills into the single slowest page
 *   view so a first call already yields actionable detail. Supply page_view_id to target
 *   a specific captured render.
 *
 * Important: debug must be enabled
 *   APEX only writes these views when debug is enabled for the session/request, and the
 *   data is retained for a limited time (typically up to two weeks, configurable). If the
 *   views are empty, the result includes guidance to enable application debugging.
 *
 * Tool input parameters:
 *   - app_id           (optional) APEX application ID. Omit to list all applications.
 *   - page_id          (optional) restrict to a specific page. Requires app_id.
 *   - page_view_id     (optional) drill into a specific captured page view's trace.
 *   - top_n            (optional, default 10) number of slowest page views, and number
 *                      of slowest steps within a drilled page view.
 *   - days_back        (optional, default 7) debug history lookback window in days.
 *   - min_execution_ms (optional, default 0) only return trace steps at least this slow.
 *
 * Tool output:
 *   JSON object example (Oracle APEX 23.2, app_id=100):
 * <pre>
 * {
 *   "privilege_warnings": [],
 *   "debug_enabled": true,
 *   "applications": [ { "application_id": 100, "application_name": "HR Portal" } ],
 *   "slowest_page_views": [
 *     { "page_view_id": 8842301, "page_id": 12, "view_timestamp": "2026-06-21T09:41:03",
 *       "elapsed_ms": 4870, "apex_user": "JSMITH", "step_count": 214 }
 *   ],
 *   "drilled_page_view_id": 8842301,
 *   "slowest_steps": [
 *     { "page_view_id": 8842301, "page_id": 12, "message_id": 51, "execution_ms": 3920,
 *       "elapsed_ms": 4010, "message_level": 4,
 *       "message": "Region: Results - SQL query ..." }
 *   ],
 *   "recommendations": [
 *     { "priority": "HIGH", "category": "DEBUG_SLOW_STEP", "page_id": 12,
 *       "execution_ms": 3920, "step": "Region: Results - SQL query ...",
 *       "message": "Step took 3920ms on page 12: \\"Region: Results ...\\". Read the page's "
 *                  + "code with get_apex_source (page_id=12) and run the SQL through query_plan_expert." }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage:
 *   - Use slowest_steps to know exactly which component to fix, then call get_apex_source
 *     for that page to read the offending code, and query_plan_expert for its SQL.
 *   - Cross-check against inspect_apex_performance: the activity log shows aggregate page
 *     timing across all users; this shows the step-level breakdown of one captured render.
 */
public class InspectApexDebug
        implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(InspectApexDebug.class);

    public static final String TOOL_NAME = "inspect_apex_debug";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** APEX debug views store times in seconds (fractional); ×1000 converts to milliseconds. */
    private static final int SECONDS_TO_MS = 1_000;

    /** Trace steps at or above this execution time (ms) are flagged HIGH in recommendations. */
    private static final int STEP_HIGH_MS = 1_000;

    /** Trace steps at or above this execution time (ms) are flagged MEDIUM in recommendations. */
    private static final int STEP_MEDIUM_MS = 250;

    private final JdbcClient jdbcClient;

    /**
     * Creates a new InspectApexDebug tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected to an Oracle DB
     */
    public InspectApexDebug(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for inspect_apex_debug.
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
                      "description": "APEX application ID to inspect. If omitted, all applications are listed but no debug data is returned."
                    },
                    "page_id": {
                      "type": "integer",
                      "description": "Restrict to a specific page ID. Requires app_id."
                    },
                    "page_view_id": {
                      "type": "integer",
                      "description": "Drill into a specific captured page view's trace and return its slowest steps. If omitted, the tool auto-drills into the single slowest page view."
                    },
                    "top_n": {
                      "type": "integer",
                      "description": "Number of slowest page views to list, and number of slowest steps to return within a drilled page view. Default: 10.",
                      "default": 10
                    },
                    "days_back": {
                      "type": "integer",
                      "description": "How many days of debug history to analyze. APEX retains debug data for a limited time. Default: 7.",
                      "default": 7
                    },
                    "min_execution_ms": {
                      "type": "integer",
                      "description": "Only return trace steps whose execution time is at least this many milliseconds. Default: 0 (all steps).",
                      "default": 0
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Reads Oracle APEX's own debug trace (APEX_DEBUG_MESSAGES, grouped by \
                        page view) to show empirically where a page render spent \
                        its time. Lists the slowest captured page views, then drills into \
                        one to surface the slowest individual steps (region/process/etc.) \
                        with per-step execution time in milliseconds, plus recommendations. \
                        Requires that APEX debug was enabled when the pages ran. \
                        Complements inspect_apex_performance (aggregate activity log) with \
                        per-render step detail. Oracle only.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the inspect_apex_debug tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the analysis parameters
     * @return a CallToolResult containing the APEX debug data as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        // APEX only runs on Oracle — fail fast for other database types
        String dbType = jdbcClient.getConfig().getDbType();
        if (!"oracle".equalsIgnoreCase(dbType)) {
            return errorResult("inspect_apex_debug requires an Oracle database. "
                    + "Current db_type is '" + dbType + "'. Oracle APEX only runs on Oracle.");
        }

        Integer appId       = getIntArg(args, "app_id", null);
        Integer pageId      = getIntArg(args, "page_id", null);
        Long    pageViewId  = getLongArg(args, "page_view_id", null);
        int     topN        = getIntArg(args, "top_n", 10);
        int     daysBack    = getIntArg(args, "days_back", 7);
        int     minExecMs   = getIntArg(args, "min_execution_ms", 0);

        log.info("Tool '{}' called: app_id={}, page_id={}, page_view_id={}, top_n={}, "
                + "days_back={}, min_execution_ms={}",
                TOOL_NAME, appId, pageId, pageViewId, topN, daysBack, minExecMs);

        try {
            Connection conn = jdbcClient.getConnection();
            List<String> warnings = new ArrayList<>();

            List<Map<String, Object>> apps = queryApplications(conn, appId, warnings);
            if (apps.isEmpty() && appId != null) {
                return errorResult("Application " + appId + " not found in APEX_APPLICATIONS. "
                        + "Verify the application ID and SELECT privilege on APEX_APPLICATIONS.");
            }

            List<Map<String, Object>> pageViews     = List.of();
            List<Map<String, Object>> slowestSteps  = List.of();
            List<Map<String, Object>> recommendations = List.of();
            Long drilledPageViewId = null;

            if (appId != null) {
                pageViews = queryPageViews(conn, appId, pageId, topN, daysBack, warnings);

                // Resolve which page view to drill into: explicit param, else the slowest
                if (pageViewId != null) {
                    drilledPageViewId = pageViewId;
                } else if (!pageViews.isEmpty()) {
                    Object pv = pageViews.get(0).get("page_view_id");
                    if (pv instanceof Number n) drilledPageViewId = n.longValue();
                }

                if (drilledPageViewId != null) {
                    slowestSteps = queryDebugMessages(conn, appId, drilledPageViewId,
                            topN, minExecMs, warnings);
                    recommendations = generateRecommendations(slowestSteps);
                }
            } else if (!apps.isEmpty()) {
                String appList = apps.stream()
                        .map(a -> a.get("application_id") + " (" + a.get("application_name") + ")")
                        .collect(Collectors.joining(", "));
                warnings.add("Set app_id to get debug data. Available applications: " + appList);
            }

            boolean debugEnabled = !pageViews.isEmpty();
            if (appId != null && !debugEnabled) {
                warnings.add("No debug page views found for application " + appId
                        + " in the last " + daysBack + " day(s). APEX debug data is only captured "
                        + "when debugging is enabled for the session/request, and is retained for a "
                        + "limited time. Enable it via the app's Edit Application Definition → "
                        + "Properties → Debugging = 'Yes', then reproduce the slow page with debug on "
                        + "(append &p_debug=YES or use the Developer Toolbar → Debug).");
            }

            ObjectNode result = JSON.createObjectNode();
            result.set("privilege_warnings", toStringArray(warnings));
            result.put("debug_enabled", debugEnabled);
            result.set("applications", toJsonArray(apps));
            result.set("slowest_page_views", toJsonArray(pageViews));
            if (drilledPageViewId != null) {
                result.put("drilled_page_view_id", drilledPageViewId);
            } else {
                result.putNull("drilled_page_view_id");
            }
            result.set("slowest_steps", toJsonArray(slowestSteps));
            result.set("recommendations", toJsonArray(recommendations));

            log.info("inspect_apex_debug: {} app(s), {} page view(s), drilled={}, "
                    + "{} step(s), {} recommendation(s), {} warning(s)",
                    apps.size(), pageViews.size(), drilledPageViewId,
                    slowestSteps.size(), recommendations.size(), warnings.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("inspect_apex_debug failed: {}", e.getMessage(), e);
            return errorResult("Error querying APEX debug data: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /**
     * Queries APEX_APPLICATIONS for the list of applications in the workspace.
     *
     * @param conn     active JDBC connection
     * @param appId    optional filter; null returns all applications
     * @param warnings mutable list for error messages
     * @return list of application records, empty if view is inaccessible or no matches
     */
    private List<Map<String, Object>> queryApplications(Connection conn, Integer appId,
            List<String> warnings) {
        String sql = "SELECT APPLICATION_ID, APPLICATION_NAME, OWNER "
                   + "FROM APEX_APPLICATIONS "
                   + (appId != null ? "WHERE APPLICATION_ID = ? " : "")
                   + "ORDER BY APPLICATION_ID";
        Object[] params = appId != null ? new Object[]{appId} : new Object[0];
        return executeQuery(conn, sql, warnings, "APEX_APPLICATIONS", params);
    }

    /**
     * Derives the slowest captured page views by aggregating APEX_DEBUG_MESSAGES.
     *
     * APEX 26 exposes no APEX_DEBUG_PAGE_VIEWS view (that name was assumed pre-verification);
     * the debug trace lives entirely in APEX_DEBUG_MESSAGES, where each row carries a
     * PAGE_VIEW_ID grouping key. A "page view" is therefore reconstructed by grouping on
     * PAGE_VIEW_ID: its total elapsed time is MAX(ELAPSED_TIME) (ELAPSED_TIME is cumulative
     * from the start of the request), its timestamp is the first message, and step_count is
     * the number of trace rows. Rows with a null PAGE_VIEW_ID (non-page-view debug context)
     * are excluded.
     *
     * Ordered by total elapsed time descending. ELAPSED_TIME (seconds) is converted to
     * milliseconds in {@link #convertSecondsColumn}.
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param pageId   optional page filter; null for all pages
     * @param topN     maximum page views to return (slowest first)
     * @param daysBack lookback window in days
     * @param warnings mutable list for error messages
     * @return page view records with elapsed_ms, ordered slowest first
     */
    private List<Map<String, Object>> queryPageViews(Connection conn, int appId, Integer pageId,
            int topN, int daysBack, List<String> warnings) {
        String innerSql = "SELECT PAGE_VIEW_ID, "
                + "MAX(PAGE_ID) AS PAGE_ID, "
                + "MIN(MESSAGE_TIMESTAMP) AS VIEW_TIMESTAMP, "
                + "MAX(ELAPSED_TIME) AS ELAPSED_TIME, "
                + "MAX(APEX_USER) AS APEX_USER, "
                + "COUNT(*) AS STEP_COUNT "
                + "FROM APEX_DEBUG_MESSAGES "
                + "WHERE APPLICATION_ID = ? "
                + "AND PAGE_VIEW_ID IS NOT NULL "
                + (pageId != null ? "AND PAGE_ID = ? " : "")
                + "AND MESSAGE_TIMESTAMP >= SYSTIMESTAMP - NUMTODSINTERVAL(?, 'DAY') "
                + "GROUP BY PAGE_VIEW_ID "
                + "ORDER BY ELAPSED_TIME DESC";
        Object[] params = pageId != null
                ? new Object[]{appId, pageId, daysBack}
                : new Object[]{appId, daysBack};
        List<Map<String, Object>> rows = executeQuery(conn,
                "SELECT * FROM (" + innerSql + ") WHERE ROWNUM <= ?",
                warnings, "APEX_DEBUG_MESSAGES", append(params, topN));
        rows.forEach(r -> convertSecondsColumn(r, "elapsed_time", "elapsed_ms"));
        return rows;
    }

    /**
     * Queries APEX_DEBUG_MESSAGES for the slowest steps within a single page view.
     *
     * Ordered by EXECUTION_TIME descending so the costliest individual steps surface
     * first. EXECUTION_TIME and ELAPSED_TIME are converted to milliseconds. The
     * min_execution_ms threshold is applied after conversion (the underlying column type
     * varies across APEX versions, so filtering in Java is safest).
     *
     * @param conn        active JDBC connection
     * @param appId       APEX application ID
     * @param pageViewId  the page view to drill into
     * @param topN        maximum steps to return (slowest first)
     * @param minExecMs   discard steps faster than this many milliseconds
     * @param warnings    mutable list for error messages
     * @return trace step records with execution_ms/elapsed_ms, ordered slowest first
     */
    private List<Map<String, Object>> queryDebugMessages(Connection conn, int appId, long pageViewId,
            int topN, int minExecMs, List<String> warnings) {
        // APEX 26 APEX_DEBUG_MESSAGES has no COMPONENT_TYPE / COMPONENT_NAME columns (those
        // were assumed pre-verification); the step identity lives in the MESSAGE text. The
        // row key is ID (aliased to message_id), not MESSAGE_ID.
        String innerSql = "SELECT PAGE_VIEW_ID, PAGE_ID, ID AS MESSAGE_ID, MESSAGE_TIMESTAMP, "
                + "EXECUTION_TIME, ELAPSED_TIME, MESSAGE_LEVEL, MESSAGE "
                + "FROM APEX_DEBUG_MESSAGES "
                + "WHERE APPLICATION_ID = ? AND PAGE_VIEW_ID = ? "
                + "ORDER BY EXECUTION_TIME DESC NULLS LAST";
        List<Map<String, Object>> rows = executeQuery(conn,
                "SELECT * FROM (" + innerSql + ") WHERE ROWNUM <= ?",
                warnings, "APEX_DEBUG_MESSAGES", new Object[]{appId, pageViewId, topN});

        rows.forEach(r -> {
            convertSecondsColumn(r, "execution_time", "execution_ms");
            convertSecondsColumn(r, "elapsed_time", "elapsed_ms");
        });

        if (minExecMs > 0) {
            rows = rows.stream()
                    .filter(r -> {
                        Object ms = r.get("execution_ms");
                        return ms instanceof Number n && n.doubleValue() >= minExecMs;
                    })
                    .collect(Collectors.toList());
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Recommendations engine
    // -------------------------------------------------------------------------

    /**
     * Generates recommendations from the slowest trace steps of a drilled page view.
     *
     * Each step at or above {@link #STEP_MEDIUM_MS} produces a DEBUG_SLOW_STEP
     * recommendation: HIGH at/above {@link #STEP_HIGH_MS}, otherwise MEDIUM. Since APEX 26
     * debug messages carry no structured component name/type, the step is identified by an
     * abbreviation of its MESSAGE text (which typically names the region/process, e.g.
     * "Region: Results - SQL query ..."), so the agent can jump to get_apex_source /
     * query_plan_expert for that page.
     *
     * @param steps the slowest steps from {@link #queryDebugMessages}, already in ms
     * @return recommendations sorted HIGH → MEDIUM
     */
    private List<Map<String, Object>> generateRecommendations(List<Map<String, Object>> steps) {
        List<Map<String, Object>> recs = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Object execObj = step.get("execution_ms");
            if (!(execObj instanceof Number execNum)) continue;
            double execMs = execNum.doubleValue();
            if (execMs < STEP_MEDIUM_MS) continue;

            Object pageId   = step.get("page_id");
            String stepText = abbreviate(String.valueOf(step.get("message")), 100);

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",       execMs >= STEP_HIGH_MS ? "HIGH" : "MEDIUM");
            rec.put("category",       "DEBUG_SLOW_STEP");
            rec.put("page_id",        pageId);
            rec.put("execution_ms",   execNum);
            rec.put("step",           stepText);
            rec.put("message", "Step took " + (long) execMs + "ms on page " + pageId
                    + ": \"" + stepText + "\". Read the page's code with get_apex_source "
                    + "(page_id=" + pageId + ") and, if the step runs SQL, pass that SQL to "
                    + "query_plan_expert for an EXPLAIN PLAN.");
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
     * Column names are lowercased for idiomatic JSON keys. CLOB values (the MESSAGE
     * column can be a CLOB) are materialized to String. Temporal types become ISO-8601
     * strings. Failures (ORA-00942 missing privilege, ORA-00904 missing column on this
     * APEX version) are recorded in warnings and return an empty list.
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
                            row.put(cols.get(i - 1), len == 0 ? "" : clob.getSubString(1, (int) Math.min(len, Integer.MAX_VALUE)));
                        } else if (val instanceof Timestamp ts) {
                            row.put(cols.get(i - 1), ts.toLocalDateTime().toString());
                        } else if (val instanceof java.sql.Date d) {
                            row.put(cols.get(i - 1), d.toLocalDate().toString());
                        } else if (val != null && val.getClass().getName().startsWith("oracle.sql.TIMESTAMP")) {
                            // Oracle TIMESTAMP WITH [LOCAL] TIME ZONE columns come back as
                            // oracle.sql.TIMESTAMPTZ / TIMESTAMPLTZ, which do NOT match java.sql.Timestamp
                            // and would otherwise serialize as "oracle.sql.TIMESTAMPTZ@<hash>". Ask the
                            // driver to normalize them to a java.sql.Timestamp for a readable ISO string.
                            Timestamp ts = rs.getTimestamp(i);
                            row.put(cols.get(i - 1), ts == null ? null : ts.toLocalDateTime().toString());
                        } else {
                            row.put(cols.get(i - 1), val);
                        }
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            warnings.add(viewName + " query failed: " + firstLine(e.getMessage()));
            log.warn("Query on {} failed: {}", viewName, e.getMessage());
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Conversion / utility helpers
    // -------------------------------------------------------------------------

    /**
     * Replaces a seconds-valued column in a row with a milliseconds-valued one.
     *
     * APEX debug views store durations in fractional seconds. This removes the original
     * {@code secondsKey} entry and inserts {@code msKey} holding the rounded millisecond
     * value, but only when the source is numeric; non-numeric/absent values are left as-is
     * (the original key is preserved so no information is lost).
     *
     * @param row        the row map to mutate
     * @param secondsKey the lowercase source column name (seconds)
     * @param msKey      the lowercase target column name (milliseconds)
     */
    private void convertSecondsColumn(Map<String, Object> row, String secondsKey, String msKey) {
        Object val = row.get(secondsKey);
        if (val instanceof Number n) {
            row.remove(secondsKey);
            row.put(msKey, Math.round(n.doubleValue() * SECONDS_TO_MS));
        }
    }

    /**
     * Returns a new array with {@code extra} appended to {@code base}.
     *
     * @param base  the original parameter array
     * @param extra the value to append
     * @return a new array of length base.length + 1
     */
    private Object[] append(Object[] base, Object extra) {
        Object[] out = Arrays.copyOf(base, base.length + 1);
        out[base.length] = extra;
        return out;
    }

    /**
     * Abbreviates a string to at most {@code max} characters, appending an ellipsis.
     *
     * @param s   the string (may be null)
     * @param max maximum length before truncation
     * @return the abbreviated string, or "" if null
     */
    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
     * Using the generic Object overload would serialize numbers as strings; this
     * dispatches to the narrowest matching typed overload.
     *
     * @param node  the target ObjectNode
     * @param key   the field name
     * @param value null, String, Long, Integer, BigDecimal, Double, Float, Boolean,
     *              or any other Object serialized via toString
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
    // Argument extraction helpers
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
     * Extracts a long argument from the tool call arguments map.
     *
     * Page view IDs can exceed the int range, so they are handled as longs.
     *
     * @param args         tool call arguments map
     * @param key          argument name
     * @param defaultValue fallback when key is absent or null
     * @return resolved long, or defaultValue
     */
    private Long getLongArg(Map<String, Object> args, String key, Long defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

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
