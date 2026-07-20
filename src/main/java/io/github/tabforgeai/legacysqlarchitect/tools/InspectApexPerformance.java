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
 * MCP tool: inspect_apex_performance
 *
 * Analyzes an Oracle APEX application for performance issues by querying
 * APEX metadata views and the APEX activity log.
 *
 * The existing tools in this MCP server (inspect_schema, query_plan_expert, etc.)
 * only see user objects via JDBC DatabaseMetaData. They are blind to the APEX
 * metadata schema that describes applications, pages, regions, LOVs, and validations.
 * This tool bridges that gap by querying the APEX dictionary views directly.
 *
 * APEX dictionary views queried:
 *   - APEX_APPLICATIONS              — application catalog (name, owner, version)
 *   - APEX_APPLICATION_PAGES         — page list (id, name, alias, authorization)
 *   - APEX_APPLICATION_PAGE_REGIONS  — region SQL sources (Reports, Grids, etc.)
 *   - APEX_WORKSPACE_ACTIVITY_LOG    — per-page-view timing data
 *   - APEX_APPLICATION_LOVS          — shared List of Values SQL queries
 *   - APEX_APPLICATION_PAGE_ITEMS    — page items with SQL sources/named LOVs
 *   - APEX_APPLICATION_PAGE_VAL      — SQL-type form validations
 *   - APEX_APPLICATION_PAGE_PROC     — page-level processes
 *   - APEX_APPLICATION_PROCESSES     — application-level processes
 *   - APEX_APPLICATION_COMPUTATIONS  — application-level computations
 *
 * Privilege model:
 *   All views are probed at startup. Missing privileges are recorded in
 *   "privilege_warnings" and produce empty sections — execution continues with
 *   whatever data is accessible.
 *
 * Tool input parameters:
 *   - app_id              (optional) APEX application ID. Omit to list all applications.
 *   - page_id             (optional) filter to a specific page. Requires app_id.
 *   - top_n               (optional, default 10) slowest pages from activity log.
 *   - include_sql         (optional, default true) include SQL from page regions.
 *   - days_back           (optional, default 30) activity log lookback window in days.
 *   - include_lovs        (optional, default true) include LOV SQL analysis.
 *   - include_validations (optional, default true) include SQL items and validations.
 *   - include_recommendations (optional, default true) include auto-generated
 *                          performance recommendations based on all collected data.
 *
 * Tool output:
 *   JSON object example (Oracle APEX 23.2, app_id=100):
 * <pre>
 * {
 *   "privilege_warnings": [],
 *   "applications": [
 *     { "application_id": 100, "application_name": "HR Portal", "owner": "HRSCHEMA" }
 *   ],
 *   "slowest_pages": [
 *     { "page_id": 12, "avg_elapsed_ms": 4250, "max_elapsed_ms": 9800, "call_count": 312,
 *       "last_accessed": "2026-05-14T09:41:00" }
 *   ],
 *   "pages": [
 *     { "page_id": 12, "page_name": "Employee Search", "page_alias": "SEARCH" }
 *   ],
 *   "regions_with_sql": [
 *     { "page_id": 12, "region_name": "Results", "region_type": "Interactive Report",
 *       "sql_query": "SELECT e.*, d.dept_name FROM employees e JOIN departments d ..." }
 *   ],
 *   "lovs": [
 *     { "lov_name": "Departments", "lov_query": "SELECT name d, id r FROM departments ORDER BY 1",
 *       "usage_count": 5 }
 *   ],
 *   "item_sources": [
 *     { "page_id": 12, "item_name": "P12_DEPT_ID", "display_as": "Select List",
 *       "list_of_values_name": "Departments", "source_type": "Database Column", "source": null }
 *   ],
 *   "validations": [
 *     { "page_id": 12, "validation_name": "Check Budget", "validation_type": "Item NOT in SQL Query",
 *       "validation_sql": "SELECT 1 FROM budget WHERE dept_id = :P12_DEPT_ID" }
 *   ],
 *   "recommendations": [
 *     { "priority": "HIGH", "category": "SLOW_PAGE", "page_id": 12,
 *       "avg_elapsed_ms": 4250, "call_count": 312,
 *       "message": "Page 12 averages 4250ms (312 calls). Region(s): Results. Run SQL through query_plan_expert." },
 *     { "priority": "HIGH", "category": "LOV_NO_WHERE", "lov_name": "Departments",
 *       "usage_count": 5, "message": "LOV 'Departments' has no WHERE clause — full scan on every load. ..." }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage — recommended workflow for diagnosing a slow APEX application:
 *   1. Call with only app_id — get slowest_pages + LOV/validation summary + recommendations.
 *   2. Re-call with page_id of the slowest page — get its region SQL.
 *   3. Pass region SQL to query_plan_expert for EXPLAIN PLAN and index suggestions.
 *   4. Use inspect_schema for index/column details on the involved tables.
 */
public class InspectApexPerformance
        implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(InspectApexPerformance.class);

    public static final String TOOL_NAME = "inspect_apex_performance";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Multiplier to convert APEX_WORKSPACE_ACTIVITY_LOG.ELAPSED_TIME (seconds) to milliseconds.
     *
     * APEX_WORKSPACE_ACTIVITY_LOG stores ELAPSED_TIME as the page view elapsed time in
     * seconds (fractional). Multiplying by 1000 converts to milliseconds, the standard unit
     * for web performance measurement.
     *
     * NOTE: verified against the APEX 26 dictionary that APEX_USER_ACTIVITY_LOG does not
     * exist on this release; APEX_WORKSPACE_ACTIVITY_LOG is the sole activity source. The
     * seconds→ms assumption still needs confirmation against a populated log (the test
     * instance had zero activity rows).
     */
    private static final int SECONDS_TO_MS = 1000;

    /**
     * Pages whose average elapsed time exceeds this threshold (ms) are flagged
     * with MEDIUM priority in the recommendations.
     */
    private static final int SLOW_PAGE_MEDIUM_MS = 1_000;

    /**
     * Pages whose average elapsed time exceeds this threshold (ms) are flagged
     * with HIGH priority in the recommendations.
     */
    private static final int SLOW_PAGE_HIGH_MS = 3_000;

    /**
     * LOVs referenced by this many or more page items are flagged as high-blast-radius
     * in the LOV_HIGH_USAGE recommendation, warranting caching consideration.
     */
    private static final int LOV_HIGH_USAGE_THRESHOLD = 3;

    private final JdbcClient jdbcClient;

    /**
     * Creates a new InspectApexPerformance tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected to an Oracle DB
     */
    public InspectApexPerformance(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for inspect_apex_performance.
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
                      "description": "APEX application ID to inspect. If omitted, all applications in the workspace are listed but no performance data is returned."
                    },
                    "page_id": {
                      "type": "integer",
                      "description": "Filter results to a specific page ID within the application. Requires app_id."
                    },
                    "top_n": {
                      "type": "integer",
                      "description": "Number of slowest pages to return from the activity log, ordered by average elapsed time descending. Default: 10.",
                      "default": 10
                    },
                    "include_sql": {
                      "type": "boolean",
                      "description": "Include SQL source from page regions (Interactive Reports, Classic Reports, Grids). Default: true.",
                      "default": true
                    },
                    "days_back": {
                      "type": "integer",
                      "description": "How many days of activity log history to analyze. Default: 30.",
                      "default": 30
                    },
                    "include_lovs": {
                      "type": "boolean",
                      "description": "Include analysis of shared List of Values SQL queries. LOVs run on every page load and are a common performance culprit. Default: true.",
                      "default": true
                    },
                    "include_validations": {
                      "type": "boolean",
                      "description": "Include page items with SQL sources/computations and SQL-type form validations. Default: true.",
                      "default": true
                    },
                    "include_processes": {
                      "type": "boolean",
                      "description": "Include page processes (PL/SQL blocks, DML processes) with their execution point and condition. Processes that always run without a condition are common hidden performance culprits. Default: true.",
                      "default": true
                    },
                    "include_recommendations": {
                      "type": "boolean",
                      "description": "Include auto-generated performance recommendations based on all collected data (slow pages, LOV patterns, SELECT *, SQL validations, unconditional processes). Default: true.",
                      "default": true
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Analyzes an Oracle APEX application for performance issues. \
                        Returns slowest pages from the activity log, SQL from page regions \
                        (Interactive Reports, Grids), shared LOV queries with usage counts, \
                        page items with SQL sources, SQL validations, and auto-generated \
                        recommendations (SLOW_PAGE, LOV_NO_WHERE, LOV_HIGH_USAGE, \
                        REGION_SELECT_STAR, VALIDATION_SQL, PROCESS_UNCONDITIONAL). \
                        Recommended workflow: call with app_id to get the full picture, \
                        then drill into the slowest page with page_id, then pass region SQL \
                        to query_plan_expert for index recommendations. Oracle only.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the inspect_apex_performance tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the analysis parameters
     * @return a CallToolResult containing the APEX performance data as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        // APEX only runs on Oracle — fail fast for other database types
        String dbType = jdbcClient.getConfig().getDbType();
        if (!"oracle".equalsIgnoreCase(dbType)) {
            return errorResult("inspect_apex_performance requires an Oracle database. "
                    + "Current db_type is '" + dbType + "'. Oracle APEX only runs on Oracle.");
        }

        Integer appId       = getIntArg(args,  "app_id",                null);
        Integer pageId      = getIntArg(args,  "page_id",               null);
        int     topN        = getIntArg(args,  "top_n",                 10);
        boolean inclSql     = getBoolArg(args, "include_sql",           true);
        int     daysBack    = getIntArg(args,  "days_back",             30);
        boolean inclLovs    = getBoolArg(args, "include_lovs",          true);
        boolean inclValid   = getBoolArg(args, "include_validations",   true);
        boolean inclProc    = getBoolArg(args, "include_processes",     true);
        boolean inclRecs    = getBoolArg(args, "include_recommendations", true);

        log.info("Tool '{}' called: app_id={}, page_id={}, top_n={}, days_back={}, "
                + "include_sql={}, include_lovs={}, include_validations={}, "
                + "include_processes={}, include_recommendations={}",
                TOOL_NAME, appId, pageId, topN, daysBack,
                inclSql, inclLovs, inclValid, inclProc, inclRecs);

        try {
            Connection conn = jdbcClient.getConnection();
            List<String> warnings = new ArrayList<>();

            // Pre-check: probe all APEX views and record any missing privileges
            checkApexPrivileges(conn, warnings);

            // Applications
            List<Map<String, Object>> apps = queryApplications(conn, appId, warnings);
            if (apps.isEmpty() && appId != null) {
                return errorResult("Application " + appId + " not found in APEX_APPLICATIONS. "
                        + "Verify the application ID and that the database user has "
                        + "SELECT privilege on APEX_APPLICATIONS.");
            }

            // All detailed sections require a known app_id
            List<Map<String, Object>> slowestPages   = List.of();
            List<Map<String, Object>> pages          = List.of();
            List<Map<String, Object>> regions        = List.of();
            List<Map<String, Object>> lovs            = List.of();
            List<Map<String, Object>> itemSources    = List.of();
            List<Map<String, Object>> validations    = List.of();
            List<Map<String, Object>> processes      = List.of();
            List<Map<String, Object>> appProcesses   = List.of();
            List<Map<String, Object>> appComputations = List.of();
            List<Map<String, Object>> recommendations = List.of();

            if (appId != null) {
                slowestPages = queryActivityLog(conn, appId, topN, daysBack, warnings);
                pages        = queryPages(conn, appId, pageId, warnings);
                if (inclSql)   regions     = queryRegionSql(conn, appId, pageId, warnings);
                if (inclLovs)  lovs        = queryLovs(conn, appId, warnings);
                if (inclValid) {
                    itemSources = queryItemSources(conn, appId, pageId, warnings);
                    validations = queryValidations(conn, appId, pageId, warnings);
                }
                if (inclProc) {
                    processes       = queryPageProcesses(conn, appId, pageId, warnings);
                    appProcesses    = queryApplicationProcesses(conn, appId, warnings);
                    appComputations = queryApplicationComputations(conn, appId, warnings);
                }
                if (inclRecs) {
                    recommendations = generateRecommendations(
                            slowestPages, regions, lovs, itemSources, validations,
                            processes, appProcesses, appComputations);
                }
            } else if (!apps.isEmpty()) {
                String appList = apps.stream()
                        .map(a -> a.get("application_id") + " (" + a.get("application_name") + ")")
                        .collect(Collectors.joining(", "));
                warnings.add("Set app_id to get performance data. "
                        + "Available applications: " + appList);
            }

            // Build JSON output
            ObjectNode result = JSON.createObjectNode();
            result.set("privilege_warnings", toStringArray(warnings));
            result.set("applications",       toJsonArray(apps));
            result.set("slowest_pages",      toJsonArray(slowestPages));
            result.set("pages",              toJsonArray(pages));
            result.set("regions_with_sql",   toJsonArray(regions));
            result.set("lovs",               toJsonArray(lovs));
            result.set("item_sources",       toJsonArray(itemSources));
            result.set("validations",        toJsonArray(validations));
            result.set("processes",           toJsonArray(processes));
            result.set("app_processes",       toJsonArray(appProcesses));
            result.set("app_computations",    toJsonArray(appComputations));
            result.set("recommendations",     toJsonArray(recommendations));

            log.info("inspect_apex_performance: {} app(s), {} slow page(s), {} region(s), "
                    + "{} LOV(s), {} item(s), {} validation(s), {} page process(es), "
                    + "{} app process(es), {} app computation(s), "
                    + "{} recommendation(s), {} warning(s)",
                    apps.size(), slowestPages.size(), regions.size(),
                    lovs.size(), itemSources.size(), validations.size(),
                    processes.size(), appProcesses.size(), appComputations.size(),
                    recommendations.size(), warnings.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("inspect_apex_performance failed: {}", e.getMessage(), e);
            return errorResult("Error querying APEX metadata: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /**
     * Pre-checks SELECT access to all eight APEX views used by this tool.
     *
     * Each view is probed with a zero-row query (WHERE ROWNUM &lt;= 0). A failure
     * (typically ORA-00942) is recorded in warnings but does not abort execution —
     * partial results from accessible views are still returned.
     *
     * @param conn     active JDBC connection
     * @param warnings mutable list to append privilege warning messages to
     */
    private void checkApexPrivileges(Connection conn, List<String> warnings) {
        String[] views = {
            "APEX_APPLICATIONS",
            "APEX_APPLICATION_PAGES",
            "APEX_APPLICATION_PAGE_REGIONS",
            "APEX_WORKSPACE_ACTIVITY_LOG",
            "APEX_APPLICATION_LOVS",
            "APEX_APPLICATION_PAGE_ITEMS",
            "APEX_APPLICATION_PAGE_VAL",
            "APEX_APPLICATION_PAGE_PROC",
            "APEX_APPLICATION_PROCESSES",
            "APEX_APPLICATION_COMPUTATIONS"
        };
        for (String view : views) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeQuery("SELECT 1 FROM " + view + " WHERE ROWNUM <= 0").close();
            } catch (SQLException e) {
                warnings.add(view + ": " + firstLine(e.getMessage())
                        + " — missing SELECT privilege or APEX is not installed");
                log.warn("APEX view not accessible: {} — {}", view, firstLine(e.getMessage()));
            }
        }
    }

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
        String sql = "SELECT APPLICATION_ID, APPLICATION_NAME, OWNER, VERSION "
                   + "FROM APEX_APPLICATIONS "
                   + (appId != null ? "WHERE APPLICATION_ID = ? " : "")
                   + "ORDER BY APPLICATION_ID";
        Object[] params = appId != null ? new Object[]{appId} : new Object[0];
        return executeQuery(conn, sql, warnings, "APEX_APPLICATIONS", params);
    }

    /**
     * Queries APEX_APPLICATION_PAGES for the pages within a given application.
     *
     * @param conn     active JDBC connection
     * @param appId    application ID to query
     * @param pageId   optional filter; null returns all pages
     * @param warnings mutable list for error messages
     * @return list of page records ordered by page ID
     */
    private List<Map<String, Object>> queryPages(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        String sql = "SELECT PAGE_ID, PAGE_NAME, PAGE_ALIAS, AUTHORIZATION_SCHEME "
                   + "FROM APEX_APPLICATION_PAGES "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "ORDER BY PAGE_ID";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGES", params);
    }

    /**
     * Queries APEX_APPLICATION_PAGE_REGIONS for regions that contain a SQL query source.
     *
     * Only regions that issue a SQL query (QUERY_TYPE_CODE = 'SQL') with a non-null
     * REGION_SOURCE are returned (Interactive Reports, Classic Reports, Interactive Grids,
     * Calendars, etc.). Static content, PL/SQL, and chart/dynamic-content regions are
     * excluded — they don't run an EXPLAIN-able SQL statement.
     * REGION_SOURCE holds the region's SQL text; it is aliased to sql_query in the output,
     * and SOURCE_TYPE (e.g. "Interactive Report", "Report") is aliased to region_type.
     * (APEX 26 has no REGION_TYPE / SQL_QUERY columns — those were assumed pre-verification.)
     *
     * @param conn     active JDBC connection
     * @param appId    application ID to query
     * @param pageId   optional filter; null returns regions from all pages
     * @param warnings mutable list for error messages
     * @return list of region records including SQL source, ordered by page then region name
     */
    private List<Map<String, Object>> queryRegionSql(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        String sql = "SELECT PAGE_ID, REGION_NAME, SOURCE_TYPE AS region_type, "
                   + "REGION_SOURCE AS sql_query "
                   + "FROM APEX_APPLICATION_PAGE_REGIONS "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND QUERY_TYPE_CODE = 'SQL' "
                   + "AND REGION_SOURCE IS NOT NULL "
                   + "ORDER BY PAGE_ID, REGION_NAME";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_REGIONS", params);
    }

    /**
     * Queries the APEX activity log for the top-N slowest pages, aggregated by page ID.
     *
     * Uses APEX_WORKSPACE_ACTIVITY_LOG — the per-page-view activity log. (APEX 26 has no
     * APEX_USER_ACTIVITY_LOG view; that name was assumed pre-verification and has been
     * removed.) If the log is empty, page-view logging may be disabled in workspace
     * administration, or no pages have been requested yet.
     *
     * ELAPSED_TIME is stored in seconds; this method converts to milliseconds by
     * multiplying by {@link #SECONDS_TO_MS}.
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param topN     maximum pages to return (slowest first)
     * @param daysBack lookback window in days
     * @param warnings mutable list for error messages
     * @return page performance records ordered by avg_elapsed_ms descending
     */
    private List<Map<String, Object>> queryActivityLog(Connection conn, int appId, int topN,
            int daysBack, List<String> warnings) {
        return queryOneActivityLog(
                conn, "APEX_WORKSPACE_ACTIVITY_LOG", "VIEW_DATE", appId, topN, daysBack, warnings);
    }

    /**
     * Executes the activity log aggregation query against a specific log view.
     *
     * The top-N limit uses Oracle's ROWNUM outer-query pattern (compatible with
     * Oracle 11g+). FETCH FIRST N ROWS ONLY requires Oracle 12c and is avoided here
     * because older APEX installations may run on older Oracle versions.
     *
     * @param conn       active JDBC connection
     * @param viewName   "APEX_WORKSPACE_ACTIVITY_LOG"
     * @param dateColumn "VIEW_DATE" — the page view timestamp column
     * @param appId      APEX application ID
     * @param topN       maximum rows to return
     * @param daysBack   lookback window in days
     * @param warnings   mutable list for error messages
     * @return aggregated performance records, empty if view is inaccessible or has no data
     */
    private List<Map<String, Object>> queryOneActivityLog(Connection conn, String viewName,
            String dateColumn, int appId, int topN, int daysBack, List<String> warnings) {
        String innerSql = "SELECT PAGE_ID, "
                + "ROUND(AVG(ELAPSED_TIME) * " + SECONDS_TO_MS + ") AS avg_elapsed_ms, "
                + "MAX(ELAPSED_TIME) * " + SECONDS_TO_MS + " AS max_elapsed_ms, "
                + "COUNT(*) AS call_count, "
                + "MAX(" + dateColumn + ") AS last_accessed "
                + "FROM " + viewName + " "
                + "WHERE APPLICATION_ID = ? "
                + "AND " + dateColumn + " >= SYSDATE - ? "
                + "GROUP BY PAGE_ID "
                + "ORDER BY avg_elapsed_ms DESC";
        return executeQuery(conn, "SELECT * FROM (" + innerSql + ") WHERE ROWNUM <= ?",
                warnings, viewName, new Object[]{appId, daysBack, topN});
    }

    /**
     * Queries APEX_APPLICATION_LOVS for shared List of Values that use SQL queries.
     *
     * Only LOVs with a non-null LIST_OF_VALUES_QUERY are returned — i.e. SQL-based LOVs,
     * the ones that hit the database on every page load and render. Static and
     * function-body LOVs (no SQL text) are excluded. (APEX 26 has no LIST_OF_VALUES_TYPE
     * column; the SQL filter is expressed on LIST_OF_VALUES_QUERY instead.)
     *
     * Usage count (how many page items reference each LOV by name) is returned
     * in the "usage_count" column via a correlated subquery against
     * APEX_APPLICATION_PAGE_ITEMS.LOV_NAMED_LOV.
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param warnings mutable list for error messages
     * @return list of SQL LOV records ordered by usage count descending
     */
    private List<Map<String, Object>> queryLovs(Connection conn, int appId,
            List<String> warnings) {
        String sql = "SELECT l.LIST_OF_VALUES_NAME AS lov_name, "
                   + "l.LIST_OF_VALUES_QUERY AS lov_query, "
                   + "(SELECT COUNT(*) FROM APEX_APPLICATION_PAGE_ITEMS i "
                   + " WHERE i.APPLICATION_ID = l.APPLICATION_ID "
                   + " AND i.LOV_NAMED_LOV = l.LIST_OF_VALUES_NAME) AS usage_count "
                   + "FROM APEX_APPLICATION_LOVS l "
                   + "WHERE l.APPLICATION_ID = ? "
                   + "AND l.LIST_OF_VALUES_QUERY IS NOT NULL "
                   + "ORDER BY usage_count DESC, l.LIST_OF_VALUES_NAME";
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_LOVS", new Object[]{appId});
    }

    /**
     * Queries APEX_APPLICATION_PAGE_ITEMS for items with SQL-based sources or named
     * LOV references.
     *
     * Two categories of items are returned:
     *   1. Items with a named shared LOV (LOV_NAMED_LOV IS NOT NULL, aliased to
     *      list_of_values_name) — used to compute per-LOV usage counts in the
     *      recommendations engine.
     *   2. Items whose source value is derived from a SQL query
     *      (ITEM_SOURCE_TYPE LIKE '%SQL%', aliased to source_type).
     *
     * (APEX 26 page items expose ITEM_SOURCE / ITEM_SOURCE_TYPE / LOV_NAMED_LOV, not the
     * SOURCE / SOURCE_TYPE / LIST_OF_VALUES_NAME / COMPUTATION_* columns assumed
     * pre-verification. Item-level computations are not part of this view.)
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param pageId   optional filter; null returns items from all pages
     * @param warnings mutable list for error messages
     * @return list of item records ordered by page ID and item name
     */
    private List<Map<String, Object>> queryItemSources(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        String sql = "SELECT PAGE_ID, ITEM_NAME, DISPLAY_AS, "
                   + "LOV_NAMED_LOV AS list_of_values_name, "
                   + "ITEM_SOURCE_TYPE AS source_type, ITEM_SOURCE AS source "
                   + "FROM APEX_APPLICATION_PAGE_ITEMS "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND (LOV_NAMED_LOV IS NOT NULL "
                   + "     OR ITEM_SOURCE_TYPE LIKE '%SQL%') "
                   + "ORDER BY PAGE_ID, ITEM_NAME";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_ITEMS", params);
    }

    /**
     * Queries APEX_APPLICATION_PAGE_VAL for validations that execute SQL.
     *
     * SQL-type validations (e.g., "Item NOT in SQL Query", "SQL Expression") run
     * on every form submission of the page they belong to. On high-traffic pages,
     * an unindexed validation query becomes a significant overhead.
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param pageId   optional filter; null returns validations from all pages
     * @param warnings mutable list for error messages
     * @return list of validation records ordered by page ID and validation name
     */
    private List<Map<String, Object>> queryValidations(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        // SQL-executing validation types on APEX 26 are the "Exists" / "NOT Exists"
        // types (VALIDATION_EXPRESSION1 holds a SQL query) plus "PL/SQL" types
        // (matched via LIKE '%SQL%'). Plain expression/regex validations don't hit the DB.
        String sql = "SELECT PAGE_ID, VALIDATION_NAME, VALIDATION_TYPE, "
                   + "VALIDATION_EXPRESSION1 AS validation_sql "
                   + "FROM APEX_APPLICATION_PAGE_VAL "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND (VALIDATION_TYPE LIKE '%SQL%' OR VALIDATION_TYPE LIKE '%Exists%') "
                   + "ORDER BY PAGE_ID, VALIDATION_NAME";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_VAL", params);
    }

    /**
     * Queries APEX_APPLICATION_PAGE_PROC for page processes with their execution points
     * and conditions.
     *
     * Page processes are PL/SQL blocks, DML operations, or API calls that APEX executes
     * at defined points in the page request lifecycle. They are a common hidden cause of
     * slow page loads because they execute outside the region rendering pipeline and are
     * therefore invisible to region-level analysis.
     *
     * Key columns:
     *   - PROCESS_POINT — when the process fires (e.g., ON_LOAD_BEFORE_HEADER,
     *     ON_SUBMIT_AFTER_COMPUTATIONS_AND_VALIDATIONS). Load-point processes block
     *     page rendering; submit-point processes run on every form submission.
     *   - CONDITION_TYPE — the condition that controls whether the process fires.
     *     A null or empty condition means the process ALWAYS runs, regardless of context.
     *   - PROCESS_SOURCE — the PL/SQL or SQL body of the process (aliased to process_sql;
     *     the sequence column is EXECUTION_SEQUENCE on APEX 26, aliased to process_sequence).
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param pageId   optional filter; null returns processes from all pages
     * @param warnings mutable list for error messages
     * @return list of process records ordered by page ID and process sequence
     */
    private List<Map<String, Object>> queryPageProcesses(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        String sql = "SELECT PAGE_ID, EXECUTION_SEQUENCE AS process_sequence, PROCESS_NAME, "
                   + "PROCESS_TYPE, PROCESS_POINT, CONDITION_TYPE, PROCESS_SOURCE AS process_sql "
                   + "FROM APEX_APPLICATION_PAGE_PROC "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "ORDER BY PAGE_ID, EXECUTION_SEQUENCE";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_PROC", params);
    }

    /**
     * Queries APEX_APPLICATION_PROCESSES for application-level processes.
     *
     * Unlike page processes (APEX_APPLICATION_PAGE_PROC), application-level processes
     * are not attached to a specific page — they execute for EVERY page request in the
     * entire application. A single unconditional application process with a slow PL/SQL
     * body will add its execution time to every page load for every user.
     *
     * This makes unconditional application processes the highest-impact performance
     * issue in APEX: one bad process degrades the entire application simultaneously.
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param warnings mutable list for error messages
     * @return list of application process records ordered by process sequence
     */
    private List<Map<String, Object>> queryApplicationProcesses(Connection conn, int appId,
            List<String> warnings) {
        String sql = "SELECT PROCESS_SEQUENCE, PROCESS_NAME, PROCESS_TYPE, "
                   + "PROCESS_POINT, CONDITION_TYPE, PROCESS AS process_sql "
                   + "FROM APEX_APPLICATION_PROCESSES "
                   + "WHERE APPLICATION_ID = ? "
                   + "ORDER BY PROCESS_SEQUENCE";
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PROCESSES", new Object[]{appId});
    }

    /**
     * Queries APEX_APPLICATION_COMPUTATIONS for application-level computations.
     *
     * Application computations set the value of page items at the application level,
     * running before each page renders. SQL-type computations execute a database query
     * on every page load, adding round-trip overhead to every request in the application.
     *
     * @param conn     active JDBC connection
     * @param appId    APEX application ID
     * @param warnings mutable list for error messages
     * @return list of application computation records ordered by computation sequence
     */
    private List<Map<String, Object>> queryApplicationComputations(Connection conn, int appId,
            List<String> warnings) {
        String sql = "SELECT COMPUTATION_SEQUENCE, COMPUTATION_ITEM, COMPUTATION_TYPE, "
                   + "COMPUTATION, CONDITION_TYPE "
                   + "FROM APEX_APPLICATION_COMPUTATIONS "
                   + "WHERE APPLICATION_ID = ? "
                   + "ORDER BY COMPUTATION_SEQUENCE";
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_COMPUTATIONS", new Object[]{appId});
    }

    // -------------------------------------------------------------------------
    // Recommendations engine
    // -------------------------------------------------------------------------

    /**
     * Analyzes all collected APEX metadata and generates a prioritized list of
     * performance recommendations.
     *
     * Recommendation categories:
     * <ul>
     *   <li>{@code SLOW_PAGE} — page with avg_elapsed_ms above threshold.
     *       HIGH if &gt; {@link #SLOW_PAGE_HIGH_MS}, MEDIUM if &gt; {@link #SLOW_PAGE_MEDIUM_MS}.
     *       Includes region names so the agent knows where to look.</li>
     *   <li>{@code LOV_NO_WHERE} — SQL LOV without a WHERE clause. Performs a full
     *       table scan on every page load and render cycle.
     *       HIGH if usage_count &ge; {@link #LOV_HIGH_USAGE_THRESHOLD}, else MEDIUM.</li>
     *   <li>{@code LOV_HIGH_USAGE} — SQL LOV used on many page items. Any performance
     *       problem with this LOV has a wide blast radius. Suggests result caching.</li>
     *   <li>{@code LOV_SELECT_STAR} — LOV using SELECT *. LOVs need exactly two columns
     *       (display value, return value); fetching all columns is wasteful.</li>
     *   <li>{@code REGION_SELECT_STAR} — region SQL using SELECT *. Fetches all columns
     *       including LOBs and columns not displayed in the report.</li>
     *   <li>{@code VALIDATION_SQL} — SQL validation on a page. Fires on every form submit;
     *       the underlying query must use indexed columns.</li>
     *   <li>{@code ITEM_SOURCE_SQL} — item whose value is derived from a SQL query source
     *       (ITEM_SOURCE_TYPE contains "SQL"). Fires on every page load; flag for review if
     *       the page is slow.</li>
     *   <li>{@code PROCESS_UNCONDITIONAL} — page-level PL/SQL or DML process with no
     *       condition. HIGH if on a slow-page load point, MEDIUM if load point, LOW if submit.</li>
     *   <li>{@code APP_PROCESS_UNCONDITIONAL} — application-level PL/SQL process with no
     *       condition. Runs on every page request for every user in the entire application.
     *       Always HIGH priority — this is the worst possible blast radius.</li>
     *   <li>{@code APP_COMPUTATION_SQL} — application-level SQL computation with no condition.
     *       Executes a database query on every page load across the entire application.
     *       MEDIUM priority.</li>
     * </ul>
     *
     * Results are sorted HIGH → MEDIUM → LOW.
     *
     * @param slowestPages    list of page performance records from the activity log
     * @param regions         list of region SQL records
     * @param lovs            list of SQL LOV records
     * @param itemSources     list of page items with SQL sources or named LOVs
     * @param validations     list of SQL validation records
     * @param processes       list of page-level process records
     * @param appProcesses    list of application-level process records
     * @param appComputations list of application-level computation records
     * @return sorted list of recommendation maps, each with priority, category, and message
     */
    private List<Map<String, Object>> generateRecommendations(
            List<Map<String, Object>> slowestPages,
            List<Map<String, Object>> regions,
            List<Map<String, Object>> lovs,
            List<Map<String, Object>> itemSources,
            List<Map<String, Object>> validations,
            List<Map<String, Object>> processes,
            List<Map<String, Object>> appProcesses,
            List<Map<String, Object>> appComputations) {

        List<Map<String, Object>> recs = new ArrayList<>();

        // Build a page_id → list of region names index for enriching SLOW_PAGE messages
        Map<Object, List<String>> regionsByPage = regions.stream()
                .filter(r -> r.get("page_id") != null)
                .collect(Collectors.groupingBy(
                        r -> r.get("page_id"),
                        Collectors.mapping(r -> String.valueOf(r.get("region_name")), Collectors.toList())
                ));

        // --- SLOW_PAGE ---
        for (Map<String, Object> page : slowestPages) {
            Number avg = (Number) page.get("avg_elapsed_ms");
            if (avg == null) continue;
            double avgMs = avg.doubleValue();
            if (avgMs < SLOW_PAGE_MEDIUM_MS) continue;

            Object pageId = page.get("page_id");
            List<String> pageRegions = regionsByPage.getOrDefault(pageId, List.of());
            String regionHint = pageRegions.isEmpty() ? ""
                    : " Region(s) with SQL: " + String.join(", ", pageRegions) + ".";

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",       avgMs >= SLOW_PAGE_HIGH_MS ? "HIGH" : "MEDIUM");
            rec.put("category",       "SLOW_PAGE");
            rec.put("page_id",        pageId);
            rec.put("avg_elapsed_ms", avg);
            rec.put("call_count",     page.get("call_count"));
            rec.put("message", "Page " + pageId + " averages " + (long) avgMs + "ms per request "
                    + "(" + page.get("call_count") + " calls in the analysis window)." + regionHint
                    + " Pass region SQL to query_plan_expert for EXPLAIN PLAN analysis.");
            recs.add(rec);
        }

        // Compute actual LOV usage from itemSources for richer recommendations
        Map<String, Long> lovUsageMap = itemSources.stream()
                .filter(i -> i.get("list_of_values_name") != null)
                .collect(Collectors.groupingBy(
                        i -> String.valueOf(i.get("list_of_values_name")),
                        Collectors.counting()
                ));

        // --- LOV recommendations ---
        for (Map<String, Object> lov : lovs) {
            String lovName  = String.valueOf(lov.get("lov_name"));
            String lovSql   = lov.get("lov_query") != null ? lov.get("lov_query").toString().toUpperCase() : "";
            long   usage    = lovUsageMap.getOrDefault(lovName, 0L);

            // LOV_NO_WHERE: SQL LOV without a WHERE clause and without a bind variable
            // (a bind variable like :ITEM implies a filtered LOV, not a full scan)
            if (!lovSql.isBlank() && !lovSql.contains("WHERE") && !lovSql.contains(":")) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("priority",    usage >= LOV_HIGH_USAGE_THRESHOLD ? "HIGH" : "MEDIUM");
                rec.put("category",    "LOV_NO_WHERE");
                rec.put("lov_name",    lovName);
                rec.put("usage_count", usage);
                rec.put("message", "LOV '" + lovName + "' has no WHERE clause — it performs a full table "
                        + "scan on every page load. Used by " + usage + " page item(s). "
                        + "Add a WHERE clause to limit results, or enable APEX result caching "
                        + "on this LOV if data changes infrequently (APEX Admin → Shared Components → LOV).");
                recs.add(rec);
            }

            // LOV_HIGH_USAGE: LOV referenced by many items — high blast radius
            if (usage >= LOV_HIGH_USAGE_THRESHOLD) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("priority",    "MEDIUM");
                rec.put("category",    "LOV_HIGH_USAGE");
                rec.put("lov_name",    lovName);
                rec.put("usage_count", usage);
                rec.put("message", "LOV '" + lovName + "' is used by " + usage + " page item(s). "
                        + "Any performance problem with this query affects all those items. "
                        + "Consider enabling APEX result caching if data changes infrequently.");
                recs.add(rec);
            }

            // LOV_SELECT_STAR: LOVs need only two columns (display, return)
            if (lovSql.contains("SELECT *")) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("priority",    "LOW");
                rec.put("category",    "LOV_SELECT_STAR");
                rec.put("lov_name",    lovName);
                rec.put("usage_count", usage);
                rec.put("message", "LOV '" + lovName + "' uses SELECT * — fetches all columns. "
                        + "A LOV needs exactly two columns: the display value and the return value. "
                        + "Select only those two columns to reduce data transfer.");
                recs.add(rec);
            }
        }

        // --- REGION_SELECT_STAR ---
        for (Map<String, Object> region : regions) {
            String sql = region.get("sql_query") != null
                    ? region.get("sql_query").toString().toUpperCase() : "";
            if (sql.contains("SELECT *")) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("priority",     "LOW");
                rec.put("category",     "REGION_SELECT_STAR");
                rec.put("page_id",      region.get("page_id"));
                rec.put("region_name",  region.get("region_name"));
                rec.put("message", "Region '" + region.get("region_name") + "' on page "
                        + region.get("page_id") + " uses SELECT * — fetches all columns, "
                        + "including LOBs and columns not displayed in the report. "
                        + "Select only the columns needed for the report.");
                recs.add(rec);
            }
        }

        // --- VALIDATION_SQL ---
        for (Map<String, Object> val : validations) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",         "LOW");
            rec.put("category",         "VALIDATION_SQL");
            rec.put("page_id",          val.get("page_id"));
            rec.put("validation_name",  val.get("validation_name"));
            rec.put("validation_type",  val.get("validation_type"));
            rec.put("message", "Validation '" + val.get("validation_name") + "' on page "
                    + val.get("page_id") + " executes a SQL query on every form submission. "
                    + "Ensure filter columns in the query are indexed. "
                    + "Pass the validation SQL to query_plan_expert to check the execution plan.");
            recs.add(rec);
        }

        // --- ITEM_SOURCE_SQL ---
        for (Map<String, Object> item : itemSources) {
            String srcType = item.get("source_type") != null
                    ? item.get("source_type").toString().toUpperCase() : "";
            if (srcType.contains("SQL")) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("priority",    "LOW");
                rec.put("category",    "ITEM_SOURCE_SQL");
                rec.put("page_id",     item.get("page_id"));
                rec.put("item_name",   item.get("item_name"));
                rec.put("message", "Item '" + item.get("item_name") + "' on page " + item.get("page_id")
                        + " derives its value from a SQL query that fires on every page load. "
                        + "If this page is slow, verify the item source query uses indexed columns.");
                recs.add(rec);
            }
        }

        // --- PROCESS_UNCONDITIONAL ---
        // Collect page IDs of slow pages for priority escalation
        Set<Object> slowPageIds = slowestPages.stream()
                .filter(p -> {
                    Number avg = (Number) p.get("avg_elapsed_ms");
                    return avg != null && avg.doubleValue() >= SLOW_PAGE_MEDIUM_MS;
                })
                .map(p -> p.get("page_id"))
                .collect(Collectors.toSet());

        for (Map<String, Object> proc : processes) {
            String procType  = proc.get("process_type")  != null ? proc.get("process_type").toString()  : "";
            String procPoint = proc.get("process_point") != null ? proc.get("process_point").toString().toUpperCase() : "";
            Object condType  = proc.get("condition_type");

            // Only flag PL/SQL and DML processes — administrative types (Clear Cache,
            // Close Popup, Reset Pagination) cannot contain slow user-written code
            boolean isPlsql = procType.toUpperCase().contains("PL/SQL")
                    || procType.toUpperCase().contains("DML")
                    || procType.toUpperCase().contains("EXECUTE");
            if (!isPlsql) continue;

            // "No condition" means: null, empty string, or the literal value "ALWAYS"
            boolean isUnconditional = condType == null
                    || condType.toString().isBlank()
                    || condType.toString().equalsIgnoreCase("ALWAYS");
            if (!isUnconditional) continue;

            boolean isLoadPoint = procPoint.contains("ON_LOAD");
            Object  pageId      = proc.get("page_id");
            boolean isSlowPage  = slowPageIds.contains(pageId);

            String priority;
            if (isLoadPoint && isSlowPage) priority = "HIGH";
            else if (isLoadPoint)          priority = "MEDIUM";
            else                           priority = "LOW";

            String pointLabel = isLoadPoint ? "page load" : "form submit";

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",      priority);
            rec.put("category",      "PROCESS_UNCONDITIONAL");
            rec.put("page_id",       pageId);
            rec.put("process_name",  proc.get("process_name"));
            rec.put("process_type",  procType);
            rec.put("process_point", proc.get("process_point"));
            rec.put("message", "Process '" + proc.get("process_name") + "' on page " + pageId
                    + " (" + procType + ", " + proc.get("process_point") + ") has no condition "
                    + "and fires on every " + pointLabel + ". "
                    + (isSlowPage ? "This page is already flagged as slow. " : "")
                    + "Add a condition to limit execution, or verify the process body "
                    + "uses indexed queries.");
            recs.add(rec);
        }

        // --- APP_PROCESS_UNCONDITIONAL ---
        // Application-level processes run on every page for every user — maximum blast radius.
        // Any unconditional PL/SQL/DML app process is always HIGH priority.
        for (Map<String, Object> proc : appProcesses) {
            String procType = proc.get("process_type") != null
                    ? proc.get("process_type").toString() : "";
            Object condType = proc.get("condition_type");

            boolean isPlsql = procType.toUpperCase().contains("PL/SQL")
                    || procType.toUpperCase().contains("DML")
                    || procType.toUpperCase().contains("EXECUTE");
            if (!isPlsql) continue;

            boolean isUnconditional = condType == null
                    || condType.toString().isBlank()
                    || condType.toString().equalsIgnoreCase("ALWAYS");
            if (!isUnconditional) continue;

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",      "HIGH");
            rec.put("category",      "APP_PROCESS_UNCONDITIONAL");
            rec.put("process_name",  proc.get("process_name"));
            rec.put("process_type",  procType);
            rec.put("process_point", proc.get("process_point"));
            rec.put("message", "Application-level process '" + proc.get("process_name")
                    + "' (" + procType + ", " + proc.get("process_point") + ") has no condition "
                    + "and runs on EVERY page request for EVERY user in the application. "
                    + "This is the highest-blast-radius performance issue possible in APEX. "
                    + "Add a condition to limit execution, or verify the process body "
                    + "completes in under a millisecond.");
            recs.add(rec);
        }

        // --- APP_COMPUTATION_SQL ---
        // Application-level SQL computations run a database query on every page load.
        for (Map<String, Object> comp : appComputations) {
            String compType = comp.get("computation_type") != null
                    ? comp.get("computation_type").toString().toUpperCase() : "";
            if (!compType.contains("SQL")) continue;

            Object condType = comp.get("condition_type");
            boolean isUnconditional = condType == null
                    || condType.toString().isBlank()
                    || condType.toString().equalsIgnoreCase("ALWAYS");
            if (!isUnconditional) continue;

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",          "MEDIUM");
            rec.put("category",          "APP_COMPUTATION_SQL");
            rec.put("computation_item",  comp.get("computation_item"));
            rec.put("computation_type",  comp.get("computation_type"));
            rec.put("message", "Application-level computation for item '"
                    + comp.get("computation_item") + "' uses a SQL query ("
                    + comp.get("computation_type") + ") with no condition — "
                    + "it executes a database query on every page load across the entire application. "
                    + "Ensure the query uses indexed columns, or add a condition to limit execution.");
            recs.add(rec);
        }

        // Sort: HIGH first, then MEDIUM, then LOW
        recs.sort(Comparator.comparingInt(r -> priorityOrder(String.valueOf(r.get("priority")))));
        return recs;
    }

    /**
     * Returns the sort order for a priority string (lower = higher priority).
     *
     * @param priority "HIGH", "MEDIUM", or "LOW"
     * @return sort key: 0 for HIGH, 1 for MEDIUM, 2 for LOW
     */
    private int priorityOrder(String priority) {
        return switch (priority) {
            case "HIGH"   -> 0;
            case "MEDIUM" -> 1;
            default       -> 2;
        };
    }

    // -------------------------------------------------------------------------
    // Generic query executor
    // -------------------------------------------------------------------------

    /**
     * Runs a parameterized SQL query and returns results as a list of ordered maps.
     *
     * Column names are lowercased to produce idiomatic JSON keys (APPLICATION_ID →
     * application_id). Values are stored as native Java types — Jackson serializes
     * them correctly as numbers, not as strings.
     *
     * Oracle type normalization:
     *   - java.sql.Timestamp → ISO-8601 string (YYYY-MM-DDTHH:MM:SS)
     *   - java.sql.Date      → ISO-8601 date string (YYYY-MM-DD)
     *   - BigDecimal         → preserved (Jackson serializes as JSON number)
     *
     * Failures (most commonly ORA-00942 for missing SELECT privileges) are added to
     * warnings and return an empty list — allowing other sections to still populate.
     *
     * @param conn      active JDBC connection
     * @param sql       parameterized SQL with ? placeholders
     * @param warnings  mutable list for error messages
     * @param viewName  human-readable name for error messages
     * @param params    bind parameters in positional order
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
                        if (val instanceof Timestamp ts) {
                            row.put(cols.get(i - 1), ts.toLocalDateTime().toString());
                        } else if (val instanceof java.sql.Date d) {
                            row.put(cols.get(i - 1), d.toLocalDate().toString());
                        } else if (val instanceof java.sql.Clob clob) {
                            // APEX source columns (REGION_SOURCE, PROCESS_SOURCE, PROCESS,
                            // LIST_OF_VALUES_QUERY, ...) are CLOBs — materialize to String so
                            // they serialize as JSON text rather than a driver object handle.
                            long len = clob.length();
                            row.put(cols.get(i - 1), len == 0 ? "" : clob.getSubString(1, (int) len));
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
    // JSON helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a list of row maps to a Jackson ArrayNode.
     *
     * @param rows list of row maps from {@link #executeQuery}
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
     * Jackson's ObjectNode.put() is overloaded per primitive type. Using the generic
     * Object overload silently serializes numbers as strings. This method dispatches
     * to the narrowest matching typed overload.
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
     * Handles Integer/Long (JSON number deserialization) and String (defensive;
     * some MCP clients serialize numbers as JSON strings).
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
     * Extracts a boolean argument from the tool call arguments map.
     *
     * @param args         tool call arguments map
     * @param key          argument name
     * @param defaultValue fallback when key is absent or null
     * @return resolved boolean, or defaultValue
     */
    private boolean getBoolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Returns the first line of an exception message.
     *
     * Oracle JDBC messages often span multiple lines with chained ORA- codes.
     * Truncating to the first line keeps warnings concise in the JSON output.
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
