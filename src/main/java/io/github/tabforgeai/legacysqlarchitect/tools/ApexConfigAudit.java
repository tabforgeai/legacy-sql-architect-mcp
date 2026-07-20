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
 * MCP tool: apex_config_audit
 *
 * Static configuration audit of an Oracle APEX application. Where
 * inspect_apex_performance reasons about the SQL a component runs and
 * inspect_apex_debug reports what actually happened at runtime, this tool inspects the
 * <em>declarative settings</em> of components for well-known performance anti-patterns —
 * the kind that don't show up in a single SQL statement but quietly tax every page render.
 *
 * It reports pure configuration facts (no SQL parsing, no runtime data required), so it
 * works on any APEX install regardless of whether activity logging or debug is enabled.
 *
 * Anti-patterns detected:
 * <ul>
 *   <li>{@code PAGINATION_ROW_COUNT} — a report region using a pagination scheme that shows
 *       a total row count ("... of Z"). APEX must run a COUNT over the full result set on
 *       every render to compute Z; on large tables this doubles the query cost. MEDIUM.</li>
 *   <li>{@code UNBOUNDED_MAX_ROWS} — a SQL region with Maximum Rows To Query unset or very
 *       high ({@literal >} {@link #MAX_ROWS_HIGH}). The region can fetch/scan a very large
 *       result set. MEDIUM when explicitly high, LOW when unset (relies on a default).</li>
 *   <li>{@code LOV_NO_CACHE} — a SQL List of Values with no result caching that is
 *       referenced by many page items ({@literal >=} {@link #LOV_USAGE_THRESHOLD}). Its
 *       query re-runs on every render of every page that uses it. LOW.</li>
 *   <li>{@code TOO_MANY_SERVER_DAS} — a page with many ({@literal >=}
 *       {@link #SERVER_DA_PER_PAGE}) "Execute Server-side Code" dynamic actions. Each is a
 *       separate AJAX round-trip to the database. MEDIUM.</li>
 *   <li>{@code MANY_SQL_REGIONS_PER_PAGE} — a page with many ({@literal >=}
 *       {@link #MANY_SQL_REGIONS}) SQL regions, few of them lazy-loaded, so they all query
 *       synchronously during the initial render. LOW.</li>
 * </ul>
 * Findings are sorted HIGH → MEDIUM → LOW.
 *
 * APEX dictionary views queried (all verified against APEX 26.1):
 *   - APEX_APPLICATIONS               — application catalog
 *   - APEX_APPLICATION_PAGE_REGIONS   — pagination scheme, max rows, lazy loading, query type
 *   - APEX_APPLICATION_LOVS           — LOV cache mode
 *   - APEX_APPLICATION_PAGE_ITEMS     — LOV usage (LOV_NAMED_LOV)
 *   - APEX_APPLICATION_PAGE_DA_ACTS   — dynamic action action types
 *
 * Tool input parameters:
 *   - app_id  (optional) APEX application ID. Omit to list applications only.
 *   - page_id (optional) restrict region/DA checks to one page. Requires app_id.
 *
 * Tool output (example, app_id=100):
 * <pre>
 * {
 *   "privilege_warnings": [],
 *   "applications": [ { "application_id": 100, "application_name": "HR Portal" } ],
 *   "summary": { "PAGINATION_ROW_COUNT": 4, "UNBOUNDED_MAX_ROWS": 7, "LOV_NO_CACHE": 2,
 *                "TOO_MANY_SERVER_DAS": 1, "MANY_SQL_REGIONS_PER_PAGE": 3, "total": 17 },
 *   "findings": [
 *     { "priority": "MEDIUM", "category": "PAGINATION_ROW_COUNT", "page_id": 12,
 *       "region_name": "Orders", "pagination_scheme": "Row Ranges X to Y of Z (with pagination)",
 *       "message": "Region 'Orders' on page 12 uses a row-count pagination scheme ..." }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage:
 *   - Each finding names the exact page/region/LOV so an agent can open it in the APEX
 *     builder, or read its code with get_apex_source, and cross-check runtime cost with
 *     apex_sql_runtime_stats.
 */
public class ApexConfigAudit
        implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(ApexConfigAudit.class);

    public static final String TOOL_NAME = "apex_config_audit";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Maximum Rows To Query above this value is flagged MEDIUM as an unbounded fetch. */
    private static final int MAX_ROWS_HIGH = 10_000;

    /** A LOV referenced by at least this many page items is "widely used" for the no-cache check. */
    private static final int LOV_USAGE_THRESHOLD = 3;

    /** A page with at least this many "Execute Server-side Code" dynamic actions is flagged. */
    private static final int SERVER_DA_PER_PAGE = 5;

    /** A page with at least this many SQL regions is flagged as a synchronous-render risk. */
    private static final int MANY_SQL_REGIONS = 6;

    private final JdbcClient jdbcClient;

    /**
     * Creates a new ApexConfigAudit tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected to an Oracle DB
     */
    public ApexConfigAudit(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for apex_config_audit.
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
                      "description": "APEX application ID to audit. If omitted, all applications are listed but no findings are produced."
                    },
                    "page_id": {
                      "type": "integer",
                      "description": "Restrict region and dynamic-action checks to a specific page ID. Requires app_id."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Static configuration audit of an Oracle APEX application for \
                        performance anti-patterns in component settings (not SQL, not \
                        runtime): row-count pagination schemes that force a COUNT over the \
                        result set, unbounded Maximum Rows To Query, widely-used LOVs with no \
                        result caching, pages with many server-side dynamic actions, and \
                        pages with many synchronous SQL regions. Needs no activity log or \
                        debug data. Complements inspect_apex_performance (SQL-level) and \
                        apex_sql_runtime_stats (runtime). Oracle only.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the apex_config_audit tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the audit parameters
     * @return a CallToolResult containing the audit findings as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        String dbType = jdbcClient.getConfig().getDbType();
        if (!"oracle".equalsIgnoreCase(dbType)) {
            return errorResult("apex_config_audit requires an Oracle database. "
                    + "Current db_type is '" + dbType + "'. Oracle APEX only runs on Oracle.");
        }

        Integer appId  = getIntArg(args, "app_id", null);
        Integer pageId = getIntArg(args, "page_id", null);

        log.info("Tool '{}' called: app_id={}, page_id={}", TOOL_NAME, appId, pageId);

        try {
            Connection conn = jdbcClient.getConnection();
            List<String> warnings = new ArrayList<>();

            List<Map<String, Object>> apps = queryApplications(conn, appId, warnings);
            if (apps.isEmpty() && appId != null) {
                return errorResult("Application " + appId + " not found in APEX_APPLICATIONS. "
                        + "Verify the application ID and SELECT privilege on APEX_APPLICATIONS.");
            }

            List<Map<String, Object>> findings = new ArrayList<>();

            if (appId != null) {
                List<Map<String, Object>> regions   = queryRegions(conn, appId, pageId, warnings);
                List<Map<String, Object>> lovs       = queryLovs(conn, appId, warnings);
                List<Map<String, Object>> serverDas  = queryServerDaCounts(conn, appId, pageId, warnings);

                findings.addAll(auditPagination(regions));
                findings.addAll(auditMaxRows(regions));
                findings.addAll(auditLovCache(lovs));
                findings.addAll(auditServerDas(serverDas));
                findings.addAll(auditManySqlRegions(regions));

                findings.sort(Comparator.comparingInt(f -> priorityOrder(String.valueOf(f.get("priority")))));
            } else if (!apps.isEmpty()) {
                String appList = apps.stream()
                        .map(a -> a.get("application_id") + " (" + a.get("application_name") + ")")
                        .collect(Collectors.joining(", "));
                warnings.add("Set app_id to run the configuration audit. Available applications: " + appList);
            }

            ObjectNode result = JSON.createObjectNode();
            result.set("privilege_warnings", toStringArray(warnings));
            result.set("applications", toJsonArray(apps));
            result.set("summary", buildSummary(findings));
            result.set("findings", toJsonArray(findings));

            log.info("apex_config_audit: {} app(s), {} finding(s), {} warning(s)",
                    apps.size(), findings.size(), warnings.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("apex_config_audit failed: {}", e.getMessage(), e);
            return errorResult("Error running APEX configuration audit: " + e.getMessage());
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
        String sql = "SELECT APPLICATION_ID, APPLICATION_NAME, OWNER, VERSION "
                   + "FROM APEX_APPLICATIONS "
                   + (appId != null ? "WHERE APPLICATION_ID = ? " : "")
                   + "ORDER BY APPLICATION_ID";
        Object[] params = appId != null ? new Object[]{appId} : new Object[0];
        return executeQuery(conn, sql, warnings, "APEX_APPLICATIONS", params);
    }

    /**
     * Queries APEX_APPLICATION_PAGE_REGIONS for SQL regions and their performance-relevant
     * declarative settings (pagination scheme, maximum rows, lazy loading).
     *
     * Only regions that issue a SQL query (QUERY_TYPE_CODE = 'SQL') are returned.
     *
     * @param conn     active JDBC connection
     * @param appId    application ID to query
     * @param pageId   optional page filter; null for all pages
     * @param warnings mutable list for error messages
     * @return list of region config records ordered by page then region name
     */
    private List<Map<String, Object>> queryRegions(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        String sql = "SELECT PAGE_ID, REGION_NAME, SOURCE_TYPE AS region_type, "
                   + "PAGINATION_SCHEME, MAXIMUM_ROWS_TO_QUERY, LAZY_LOADING "
                   + "FROM APEX_APPLICATION_PAGE_REGIONS "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND QUERY_TYPE_CODE = 'SQL' "
                   + "ORDER BY PAGE_ID, REGION_NAME";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_REGIONS", params);
    }

    /**
     * Queries APEX_APPLICATION_LOVS for SQL LOVs, their cache mode, and usage count.
     *
     * Usage count is the number of page items whose named LOV (LOV_NAMED_LOV) references
     * this LOV. Only SQL LOVs (LIST_OF_VALUES_QUERY IS NOT NULL) are returned.
     *
     * @param conn     active JDBC connection
     * @param appId    application ID to query
     * @param warnings mutable list for error messages
     * @return list of LOV config records ordered by usage count descending
     */
    private List<Map<String, Object>> queryLovs(Connection conn, int appId,
            List<String> warnings) {
        String sql = "SELECT l.LIST_OF_VALUES_NAME AS lov_name, l.CACHE_MODE, "
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
     * Counts "Execute Server-side Code" dynamic action actions per page.
     *
     * Each such action is a separate AJAX round-trip to the database; many on one page
     * multiply request overhead.
     *
     * @param conn     active JDBC connection
     * @param appId    application ID to query
     * @param pageId   optional page filter; null for all pages
     * @param warnings mutable list for error messages
     * @return list of {page_id, server_da_count} records ordered by count descending
     */
    private List<Map<String, Object>> queryServerDaCounts(Connection conn, int appId, Integer pageId,
            List<String> warnings) {
        String sql = "SELECT PAGE_ID, COUNT(*) AS server_da_count "
                   + "FROM APEX_APPLICATION_PAGE_DA_ACTS "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND ACTION_NAME = 'Execute Server-side Code' "
                   + "GROUP BY PAGE_ID "
                   + "ORDER BY COUNT(*) DESC";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        return executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_DA_ACTS", params);
    }

    // -------------------------------------------------------------------------
    // Audit rules
    // -------------------------------------------------------------------------

    /**
     * PAGINATION_ROW_COUNT: flags SQL regions whose pagination scheme shows a total row
     * count ("... of Z"), forcing APEX to COUNT the full result set on every render.
     *
     * @param regions region config records from {@link #queryRegions}
     * @return MEDIUM findings, one per offending region
     */
    private List<Map<String, Object>> auditPagination(List<Map<String, Object>> regions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : regions) {
            String scheme = str(r.get("pagination_scheme"));
            // Row-count schemes contain " of Z" (e.g. "Row Ranges X to Y of Z (with pagination)")
            if (scheme.toLowerCase().contains(" of z")) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("priority",          "MEDIUM");
                f.put("category",          "PAGINATION_ROW_COUNT");
                f.put("page_id",           r.get("page_id"));
                f.put("region_name",       r.get("region_name"));
                f.put("pagination_scheme", r.get("pagination_scheme"));
                f.put("message", "Region '" + r.get("region_name") + "' on page " + r.get("page_id")
                        + " uses a row-count pagination scheme (\"" + scheme + "\"). APEX runs a "
                        + "COUNT over the entire result set on every render to compute the total. "
                        + "On large tables, switch to an 'X to Y' scheme without a total count.");
                out.add(f);
            }
        }
        return out;
    }

    /**
     * UNBOUNDED_MAX_ROWS: flags SQL regions with Maximum Rows To Query unset (LOW) or
     * greater than {@link #MAX_ROWS_HIGH} (MEDIUM).
     *
     * @param regions region config records from {@link #queryRegions}
     * @return findings, one per offending region
     */
    private List<Map<String, Object>> auditMaxRows(List<Map<String, Object>> regions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : regions) {
            Object maxObj = r.get("maximum_rows_to_query");
            boolean unset = maxObj == null;
            boolean high  = maxObj instanceof Number n && n.longValue() > MAX_ROWS_HIGH;
            if (!unset && !high) continue;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("priority",              high ? "MEDIUM" : "LOW");
            f.put("category",              "UNBOUNDED_MAX_ROWS");
            f.put("page_id",               r.get("page_id"));
            f.put("region_name",           r.get("region_name"));
            f.put("maximum_rows_to_query", maxObj);
            f.put("message", "Region '" + r.get("region_name") + "' on page " + r.get("page_id")
                    + (unset ? " has no Maximum Rows To Query set — it relies on a default and can "
                             + "fetch a large result set."
                             : " allows up to " + maxObj + " rows to be queried, a large fetch/scan.")
                    + " Set a conservative Maximum Rows To Query to bound the work per render.");
            out.add(f);
        }
        return out;
    }

    /**
     * LOV_NO_CACHE: flags widely-used SQL LOVs (usage {@literal >=}
     * {@link #LOV_USAGE_THRESHOLD}) with no result caching configured.
     *
     * @param lovs LOV config records from {@link #queryLovs}
     * @return LOW findings, one per offending LOV
     */
    private List<Map<String, Object>> auditLovCache(List<Map<String, Object>> lovs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : lovs) {
            long usage = l.get("usage_count") instanceof Number n ? n.longValue() : 0;
            String cache = str(l.get("cache_mode"));
            boolean cached = !cache.isBlank() && !cache.equalsIgnoreCase("NOCACHE");
            if (cached || usage < LOV_USAGE_THRESHOLD) continue;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("priority",    "LOW");
            f.put("category",    "LOV_NO_CACHE");
            f.put("lov_name",    l.get("lov_name"));
            f.put("usage_count", l.get("usage_count"));
            f.put("message", "LOV '" + l.get("lov_name") + "' is referenced by " + usage
                    + " page item(s) and has no result caching. Its query re-runs on every render "
                    + "of every page that uses it. Enable LOV caching if the data changes "
                    + "infrequently (Shared Components → List of Values → Settings → Caching).");
            out.add(f);
        }
        return out;
    }

    /**
     * TOO_MANY_SERVER_DAS: flags pages with at least {@link #SERVER_DA_PER_PAGE}
     * "Execute Server-side Code" dynamic actions.
     *
     * @param serverDas per-page counts from {@link #queryServerDaCounts}
     * @return MEDIUM findings, one per offending page
     */
    private List<Map<String, Object>> auditServerDas(List<Map<String, Object>> serverDas) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : serverDas) {
            long count = d.get("server_da_count") instanceof Number n ? n.longValue() : 0;
            if (count < SERVER_DA_PER_PAGE) continue;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("priority",        "MEDIUM");
            f.put("category",        "TOO_MANY_SERVER_DAS");
            f.put("page_id",         d.get("page_id"));
            f.put("server_da_count", d.get("server_da_count"));
            f.put("message", "Page " + d.get("page_id") + " has " + count + " 'Execute Server-side "
                    + "Code' dynamic actions. Each is a separate AJAX round-trip to the database. "
                    + "Consolidate related server-side logic into fewer actions or move it into a "
                    + "single page process where possible.");
            out.add(f);
        }
        return out;
    }

    /**
     * MANY_SQL_REGIONS_PER_PAGE: flags pages with at least {@link #MANY_SQL_REGIONS} SQL
     * regions where few are lazy-loaded, so they all query synchronously on initial render.
     *
     * @param regions region config records from {@link #queryRegions}
     * @return LOW findings, one per offending page
     */
    private List<Map<String, Object>> auditManySqlRegions(List<Map<String, Object>> regions) {
        // Group SQL regions by page and count how many are NOT lazy-loaded.
        Map<Object, long[]> byPage = new LinkedHashMap<>(); // page -> [total, nonLazy]
        for (Map<String, Object> r : regions) {
            Object page = r.get("page_id");
            long[] c = byPage.computeIfAbsent(page, k -> new long[2]);
            c[0]++;
            if (!"Yes".equalsIgnoreCase(str(r.get("lazy_loading")))) c[1]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Object, long[]> e : byPage.entrySet()) {
            long total = e.getValue()[0];
            long nonLazy = e.getValue()[1];
            if (total < MANY_SQL_REGIONS) continue;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("priority",        "LOW");
            f.put("category",        "MANY_SQL_REGIONS_PER_PAGE");
            f.put("page_id",         e.getKey());
            f.put("sql_region_count", total);
            f.put("non_lazy_count",  nonLazy);
            f.put("message", "Page " + e.getKey() + " has " + total + " SQL regions, " + nonLazy
                    + " of them not lazy-loaded — they all query synchronously during the initial "
                    + "render. Enable Lazy Loading on regions below the fold, or split the page.");
            out.add(f);
        }
        return out;
    }

    /**
     * Builds the summary object: a count per category plus a total.
     *
     * @param findings the full findings list
     * @return an ObjectNode of category → count, plus "total"
     */
    private ObjectNode buildSummary(List<Map<String, Object>> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> f : findings) {
            counts.merge(String.valueOf(f.get("category")), 1, Integer::sum);
        }
        ObjectNode summary = JSON.createObjectNode();
        counts.forEach(summary::put);
        summary.put("total", findings.size());
        return summary;
    }

    /**
     * Returns the sort order for a priority string (lower = higher priority).
     *
     * @param priority "HIGH", "MEDIUM", or "LOW"
     * @return 0 for HIGH, 1 for MEDIUM, 2 for LOW
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
     * Column names are lowercased for idiomatic JSON keys. CLOB values are materialized to
     * String; temporal types become ISO-8601 strings. Failures (ORA-00942 missing
     * privilege, ORA-00904 missing column) are recorded in warnings and return an empty list.
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
