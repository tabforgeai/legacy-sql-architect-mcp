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
 * MCP tool: get_apex_source
 *
 * Extracts the embedded code/logic of an Oracle APEX application across all of its
 * code-bearing components, the way {@code get_procedure_source} does for database
 * stored code (ALL_SOURCE).
 *
 * Motivation:
 *   {@code get_procedure_source} reads PROCEDURE/FUNCTION/PACKAGE/TRIGGER/TYPE from
 *   ALL_SOURCE — that is database-level PL/SQL. It is blind to the code that lives
 *   *inside* an APEX application: region SQL, page-process PL/SQL, computations,
 *   validations, item source/default expressions, branches, dynamic-action PL/SQL,
 *   authorization schemes, and application-level processes/computations. None of that
 *   is in ALL_SOURCE — it lives in the APEX dictionary views.
 *
 *   {@code inspect_apex_performance} extracts a few of these fragments, but only
 *   incidentally and filtered through a performance lens (SQL_QUERY regions only,
 *   SQL-type validations only, PL/SQL processes only). This tool is code-centric:
 *   it returns the complete logic so an agent can read and understand what the
 *   application actually does — for diagnosis, impact analysis, or migration.
 *
 * Cross-version robustness:
 *   APEX dictionary views and their columns change between APEX releases
 *   (19.x → 24.x). Rather than hard-coding a fixed column list (which would fail with
 *   ORA-00904 on a different version), this tool first introspects ALL_TAB_COLUMNS
 *   for each view and selects only the columns that actually exist on this instance.
 *   Each component declares several candidate code/identity columns; the ones present
 *   are used, the rest are silently skipped. Inaccessible views are reported in
 *   "privilege_warnings" and produce empty sections — execution always continues.
 *
 * Tool input parameters:
 *   - app_id          (optional) APEX application ID. Omit to list all applications.
 *   - page_id         (optional) restrict to a single page. Requires app_id.
 *                     Only page-scoped component types are affected.
 *   - component_types (optional) array of section keys to include. Omit for all.
 *                     Valid keys: regions, processes, computations, validations,
 *                     items, branches, dynamic_actions, dynamic_action_actions,
 *                     lovs, authorizations, app_processes, app_computations.
 *   - search          (optional) case-insensitive substring. Only components whose
 *                     code contains the term are returned — a grep across the app's
 *                     logic (e.g. find every place that calls "MY_PKG.FOO").
 *   - max_code_length (optional, default 0 = unlimited) truncate each code value to
 *                     this many characters to bound the payload for large CLOBs.
 *
 * Tool output:
 *   JSON object example (Oracle APEX 23.2, app_id=100, search="GET_SALARY"):
 * <pre>
 * {
 *   "privilege_warnings": [],
 *   "applications": [
 *     { "application_id": 100, "application_name": "HR Portal", "owner": "HRSCHEMA" }
 *   ],
 *   "summary": { "processes": 1, "regions": 1, "total": 2 },
 *   "components": {
 *     "regions": [
 *       { "component_type": "regions", "page_id": 12, "region_name": "Results",
 *         "region_type": "Interactive Report",
 *         "sql_query": "SELECT e.empno, hr.get_salary(e.empno) sal FROM emp e" }
 *     ],
 *     "processes": [
 *       { "component_type": "processes", "page_id": 12, "process_name": "Recalc",
 *         "process_type": "PL/SQL", "process_point": "ON_SUBMIT_AFTER_...",
 *         "condition_type": null,
 *         "process_sql": "BEGIN :P12_TOTAL := hr.get_salary(:P12_EMPNO); END;" }
 *     ]
 *   }
 * }
 * </pre>
 *
 * Downstream usage:
 *   - Pair with inspect_apex_performance: when that tool flags a slow page or an
 *     unconditional process, call get_apex_source with that page_id to read the full
 *     PL/SQL body behind the flag.
 *   - Use search to perform impact analysis across the whole application before a
 *     database change (e.g. renaming a column or package).
 *   - Pass any returned SQL to query_plan_expert, or any called package/procedure name
 *     to get_procedure_source, to follow the logic down into the database tier.
 */
public class GetApexSource
        implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(GetApexSource.class);

    public static final String TOOL_NAME = "get_apex_source";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Describes one code-bearing APEX component type.
     *
     * The {@code idColumns} and {@code codeColumns} lists are <em>candidates</em>:
     * each is intersected with the columns that actually exist in {@code viewName}
     * on the target instance (see {@link #discoverColumns}). Listing extra candidate
     * names is safe and is how cross-version coverage is achieved — a column that does
     * not exist on this APEX version is simply ignored.
     *
     * @param sectionKey  stable key used both as the JSON section name and as the
     *                    value accepted in the component_types input parameter
     * @param label       human-readable label for log/warning messages
     * @param viewName    APEX dictionary view to query (uppercase)
     * @param hasPageId   whether the view is page-scoped (has a PAGE_ID column);
     *                    application-level views (LOVs, app processes) are not
     * @param idColumns   candidate identity/metadata columns (name, type, sequence, ...)
     * @param codeColumns candidate code-bearing columns (SQL/PLSQL/expressions)
     */
    private record ApexComponent(
            String sectionKey,
            String label,
            String viewName,
            boolean hasPageId,
            List<String> idColumns,
            List<String> codeColumns) {
    }

    /**
     * Registry of every code-bearing APEX component this tool can extract.
     *
     * Column names reflect APEX 20–24 dictionary views. Because columns are filtered
     * against ALL_TAB_COLUMNS at runtime, candidate columns that are absent on a given
     * APEX version cause no error — they are simply omitted from the SELECT.
     */
    private static final List<ApexComponent> COMPONENTS = List.of(
            new ApexComponent("regions", "Page Regions",
                    "APEX_APPLICATION_PAGE_REGIONS", true,
                    List.of("PAGE_ID", "REGION_NAME", "REGION_TYPE", "REGION_SUB_TYPE",
                            "SOURCE_TYPE", "DISPLAY_SEQUENCE"),
                    List.of("SQL_QUERY", "REGION_SOURCE", "FUNCTION_BODY_LANGUAGE")),

            new ApexComponent("processes", "Page Processes",
                    "APEX_APPLICATION_PAGE_PROC", true,
                    List.of("PAGE_ID", "PROCESS_NAME", "PROCESS_TYPE", "PROCESS_POINT",
                            "CONDITION_TYPE", "PROCESS_SEQUENCE"),
                    List.of("PROCESS_SQL", "PROCESS_CLOB", "CONDITION_EXPRESSION1")),

            new ApexComponent("computations", "Page Computations",
                    "APEX_APPLICATION_PAGE_COMP", true,
                    List.of("PAGE_ID", "COMPUTATION_ITEM", "COMPUTATION_TYPE",
                            "COMPUTATION_POINT", "CONDITION_TYPE", "COMPUTATION_SEQUENCE"),
                    List.of("COMPUTATION", "COMPUTATION_PROCESSED", "CONDITION_EXPRESSION1")),

            new ApexComponent("validations", "Page Validations",
                    "APEX_APPLICATION_PAGE_VAL", true,
                    List.of("PAGE_ID", "VALIDATION_NAME", "VALIDATION_TYPE", "ASSOCIATED_ITEM",
                            "CONDITION_TYPE", "ERROR_MESSAGE", "VALIDATION_SEQUENCE"),
                    List.of("VALIDATION_EXPRESSION1", "VALIDATION_EXPRESSION2",
                            "CONDITION_EXPRESSION1")),

            new ApexComponent("items", "Page Items",
                    "APEX_APPLICATION_PAGE_ITEMS", true,
                    List.of("PAGE_ID", "ITEM_NAME", "DISPLAY_AS", "SOURCE_TYPE",
                            "LIST_OF_VALUES_NAME", "CONDITION_TYPE"),
                    List.of("SOURCE", "DEFAULT_VALUE", "LOV_DEFINITION", "COMPUTATION",
                            "POST_CALCULATION_COMPUTATION", "CONDITION_EXPRESSION1")),

            new ApexComponent("branches", "Page Branches",
                    "APEX_APPLICATION_PAGE_BRANCHES", true,
                    List.of("PAGE_ID", "BRANCH_NAME", "BRANCH_TYPE", "BRANCH_POINT",
                            "CONDITION_TYPE", "BRANCH_SEQUENCE"),
                    List.of("BRANCH_TARGET", "BRANCH_SQL", "CONDITION_EXPRESSION1")),

            new ApexComponent("dynamic_actions", "Dynamic Actions",
                    "APEX_APPLICATION_PAGE_DA", true,
                    List.of("PAGE_ID", "DYNAMIC_ACTION_NAME", "EVENT_NAME",
                            "TRIGGERING_ELEMENT_TYPE", "CONDITION_TYPE", "DA_SEQUENCE"),
                    List.of("TRIGGERING_CONDITION_TYPE", "CLIENT_CONDITION_EXPRESSION",
                            "CONDITION_EXPRESSION1")),

            new ApexComponent("dynamic_action_actions", "Dynamic Action Actions",
                    "APEX_APPLICATION_PAGE_DA_ACTS", true,
                    List.of("PAGE_ID", "ACTION_NAME", "ACTION_SEQUENCE", "EXECUTION_TYPE",
                            "AFFECTED_ELEMENTS_TYPE"),
                    List.of("ATTRIBUTE_01", "ATTRIBUTE_02", "ATTRIBUTE_03")),

            new ApexComponent("lovs", "Shared List of Values",
                    "APEX_APPLICATION_LOVS", false,
                    List.of("LIST_OF_VALUES_NAME", "LIST_OF_VALUES_TYPE"),
                    List.of("LIST_OF_VALUES_QUERY")),

            new ApexComponent("authorizations", "Authorization Schemes",
                    "APEX_APPLICATION_AUTHORIZATION", false,
                    List.of("AUTHORIZATION_SCHEME_NAME", "SCHEME_TYPE", "CACHING_TYPE"),
                    List.of("ATTRIBUTE_01", "ATTRIBUTE_02", "VALIDATION_EXPRESSION")),

            new ApexComponent("app_processes", "Application Processes",
                    "APEX_APPLICATION_PROCESSES", false,
                    List.of("PROCESS_NAME", "PROCESS_TYPE", "PROCESS_POINT",
                            "CONDITION_TYPE", "PROCESS_SEQUENCE"),
                    List.of("PROCESS_SQL", "CONDITION_EXPRESSION1")),

            new ApexComponent("app_computations", "Application Computations",
                    "APEX_APPLICATION_COMPUTATIONS", false,
                    List.of("COMPUTATION_ITEM", "COMPUTATION_TYPE", "CONDITION_TYPE",
                            "COMPUTATION_SEQUENCE"),
                    List.of("COMPUTATION", "CONDITION_EXPRESSION1"))
    );

    /** Set of valid section keys, for validating the component_types input parameter. */
    private static final Set<String> VALID_KEYS = COMPONENTS.stream()
            .map(ApexComponent::sectionKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private final JdbcClient jdbcClient;

    /**
     * Creates a new GetApexSource tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected to an Oracle DB
     */
    public GetApexSource(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for get_apex_source.
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
                      "description": "APEX application ID whose source to extract. If omitted, all applications are listed but no code is returned."
                    },
                    "page_id": {
                      "type": "integer",
                      "description": "Restrict page-scoped components to a single page ID. Requires app_id. Application-level components (LOVs, authorizations, app processes/computations) are returned regardless."
                    },
                    "component_types": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "Subset of component types to return. Omit for all. Valid: regions, processes, computations, validations, items, branches, dynamic_actions, dynamic_action_actions, lovs, authorizations, app_processes, app_computations."
                    },
                    "search": {
                      "type": "string",
                      "description": "Case-insensitive substring filter. Only components whose code contains this term are returned — a grep across the application's logic (e.g. a package or column name)."
                    },
                    "max_code_length": {
                      "type": "integer",
                      "description": "Truncate each code value to this many characters to bound the payload for large CLOBs. Default 0 = unlimited.",
                      "default": 0
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Extracts the embedded code/logic of an Oracle APEX application: \
                        region SQL, page-process PL/SQL, computations, validations, item \
                        sources/defaults, branches, dynamic-action PL/SQL, shared LOV \
                        queries, authorization schemes, and application-level \
                        processes/computations. This is the APEX counterpart to \
                        get_procedure_source (which only sees database ALL_SOURCE). \
                        Supports a 'search' substring for grep-style impact analysis across \
                        the whole app, and filtering by component_types and page_id. \
                        Column sets are introspected per APEX version, so it tolerates \
                        19.x–24.x dictionary differences. Oracle only.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the get_apex_source tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the extraction parameters
     * @return a CallToolResult containing the APEX source code as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        // APEX only runs on Oracle — fail fast for other database types
        String dbType = jdbcClient.getConfig().getDbType();
        if (!"oracle".equalsIgnoreCase(dbType)) {
            return errorResult("get_apex_source requires an Oracle database. "
                    + "Current db_type is '" + dbType + "'. Oracle APEX only runs on Oracle.");
        }

        Integer appId      = getIntArg(args, "app_id", null);
        Integer pageId     = getIntArg(args, "page_id", null);
        int     maxCodeLen = getIntArg(args, "max_code_length", 0);
        String  search     = getStringArg(args, "search");
        List<String> requestedTypes = getStringListArg(args, "component_types");

        log.info("Tool '{}' called: app_id={}, page_id={}, component_types={}, search={}, max_code_length={}",
                TOOL_NAME, appId, pageId, requestedTypes, search, maxCodeLen);

        try {
            Connection conn = jdbcClient.getConnection();
            List<String> warnings = new ArrayList<>();

            // Resolve which component types to extract, warning on unknown keys
            List<ApexComponent> selected = resolveComponents(requestedTypes, warnings);

            // Applications list (for context, and to validate app_id)
            List<Map<String, Object>> apps = queryApplications(conn, appId, warnings);
            if (apps.isEmpty() && appId != null) {
                return errorResult("Application " + appId + " not found in APEX_APPLICATIONS. "
                        + "Verify the application ID and that the database user has "
                        + "SELECT privilege on APEX_APPLICATIONS.");
            }

            ObjectNode components = JSON.createObjectNode();
            ObjectNode summary    = JSON.createObjectNode();
            int total = 0;

            if (appId != null) {
                String normalizedSearch = (search != null && !search.isBlank())
                        ? search.toLowerCase() : null;

                for (ApexComponent comp : selected) {
                    List<Map<String, Object>> rows = extractComponent(
                            conn, comp, appId, pageId, normalizedSearch, maxCodeLen, warnings);
                    components.set(comp.sectionKey(), toJsonArray(rows));
                    summary.put(comp.sectionKey(), rows.size());
                    total += rows.size();
                }
            } else if (!apps.isEmpty()) {
                String appList = apps.stream()
                        .map(a -> a.get("application_id") + " (" + a.get("application_name") + ")")
                        .collect(Collectors.joining(", "));
                warnings.add("Set app_id to extract source. Available applications: " + appList);
            }
            summary.put("total", total);

            ObjectNode result = JSON.createObjectNode();
            result.set("privilege_warnings", toStringArray(warnings));
            result.set("applications",       toJsonArray(apps));
            result.set("summary",            summary);
            result.set("components",         components);

            log.info("get_apex_source: {} app(s), {} component type(s) extracted, "
                    + "{} total component(s), {} warning(s)",
                    apps.size(), selected.size(), total, warnings.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("get_apex_source failed: {}", e.getMessage(), e);
            return errorResult("Error extracting APEX source: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Component resolution & extraction
    // -------------------------------------------------------------------------

    /**
     * Resolves the requested component_types into the matching {@link ApexComponent}
     * registry entries. A null or empty request selects all components. Unknown keys
     * are recorded in warnings and ignored.
     *
     * @param requestedTypes the raw component_types values from the request, or null
     * @param warnings       mutable list for unknown-key warnings
     * @return the ordered list of components to extract
     */
    private List<ApexComponent> resolveComponents(List<String> requestedTypes, List<String> warnings) {
        if (requestedTypes == null || requestedTypes.isEmpty()) {
            return COMPONENTS;
        }
        Set<String> wanted = requestedTypes.stream()
                .map(s -> s == null ? "" : s.trim().toLowerCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String key : wanted) {
            if (!VALID_KEYS.contains(key)) {
                warnings.add("Unknown component_type '" + key + "' ignored. Valid types: "
                        + String.join(", ", VALID_KEYS));
            }
        }
        List<ApexComponent> selected = COMPONENTS.stream()
                .filter(c -> wanted.contains(c.sectionKey()))
                .collect(Collectors.toList());
        return selected.isEmpty() ? COMPONENTS : selected;
    }

    /**
     * Extracts all rows of a single component type for the given application.
     *
     * The SELECT column list is built from the component's candidate columns
     * intersected with the columns that actually exist in the view on this instance.
     * If the view is inaccessible (missing privilege or not present on this APEX
     * version) or has none of the expected code columns, a warning is recorded and an
     * empty list is returned — never an exception.
     *
     * @param conn       active JDBC connection
     * @param comp       the component definition to extract
     * @param appId      APEX application ID
     * @param pageId     optional page filter (ignored for application-level components)
     * @param search     optional lowercase substring; rows whose code contains it are kept
     * @param maxCodeLen truncate each code value to this length (0 = unlimited)
     * @param warnings   mutable list for error/skip messages
     * @return list of component rows (lowercased column → value), empty on any failure
     */
    private List<Map<String, Object>> extractComponent(Connection conn, ApexComponent comp,
            int appId, Integer pageId, String search, int maxCodeLen, List<String> warnings) {

        Set<String> existing = discoverColumns(conn, comp.viewName(), warnings);
        if (existing.isEmpty()) {
            // discoverColumns already recorded a warning
            return List.of();
        }

        // Keep only candidate columns that exist on this instance, preserving order
        List<String> idCols   = comp.idColumns().stream()
                .filter(existing::contains).collect(Collectors.toList());
        List<String> codeCols = comp.codeColumns().stream()
                .filter(existing::contains).collect(Collectors.toList());

        if (codeCols.isEmpty()) {
            warnings.add(comp.label() + " (" + comp.viewName() + "): none of the expected "
                    + "code columns exist on this APEX version — section skipped. "
                    + "Expected one of: " + String.join(", ", comp.codeColumns()));
            return List.of();
        }

        // Build the SELECT list: identity columns first, then code columns
        List<String> selectCols = new ArrayList<>(idCols);
        selectCols.addAll(codeCols);

        boolean filterByPage = comp.hasPageId() && pageId != null && existing.contains("PAGE_ID");

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectCols))
                .append(" FROM ").append(comp.viewName())
                .append(" WHERE APPLICATION_ID = ?");
        if (filterByPage) {
            sql.append(" AND PAGE_ID = ?");
        }
        // Stable ordering: PAGE_ID then the first identity column when available
        List<String> orderBy = new ArrayList<>();
        if (comp.hasPageId() && existing.contains("PAGE_ID")) orderBy.add("PAGE_ID");
        if (!idCols.isEmpty() && !idCols.get(0).equals("PAGE_ID")) orderBy.add(idCols.get(0));
        if (!orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderBy));
        }

        Object[] params = filterByPage ? new Object[]{appId, pageId} : new Object[]{appId};

        List<Map<String, Object>> rows = executeQuery(conn, sql.toString(), warnings,
                comp.viewName(), params, maxCodeLen);

        // Tag each row with its component type and apply the search filter
        List<String> lowerCodeCols = codeCols.stream()
                .map(String::toLowerCase).collect(Collectors.toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (search != null && !codeMatches(row, lowerCodeCols, search)) {
                continue;
            }
            Map<String, Object> tagged = new LinkedHashMap<>();
            tagged.put("component_type", comp.sectionKey());
            tagged.putAll(row);
            out.add(tagged);
        }
        return out;
    }

    /**
     * Returns true if any of the row's code columns contains the (already lowercased)
     * search term.
     *
     * @param row          a result row (lowercased column → value)
     * @param lowerCodeCols lowercased code-column names to inspect
     * @param search       lowercased search substring
     * @return true if the term is found in any code column
     */
    private boolean codeMatches(Map<String, Object> row, List<String> lowerCodeCols, String search) {
        for (String col : lowerCodeCols) {
            Object v = row.get(col);
            if (v != null && v.toString().toLowerCase().contains(search)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the set of column names (uppercase) that exist in the given view,
     * as seen through ALL_TAB_COLUMNS.
     *
     * This is the mechanism that makes the tool tolerant of APEX-version dictionary
     * differences: the per-component candidate column lists are intersected with this
     * set before any data SELECT is built, so a column absent on this version is never
     * referenced. An inaccessible or non-existent view yields an empty set and a
     * recorded warning.
     *
     * @param conn     active JDBC connection
     * @param viewName the APEX view name (uppercase)
     * @param warnings mutable list for the "view not accessible" warning
     * @return uppercase column names present in the view, empty if none/inaccessible
     */
    private Set<String> discoverColumns(Connection conn, String viewName, List<String> warnings) {
        Set<String> cols = new HashSet<>();
        String sql = "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1).toUpperCase());
                }
            }
        } catch (SQLException e) {
            warnings.add("Could not read columns of " + viewName + ": " + firstLine(e.getMessage()));
            log.warn("ALL_TAB_COLUMNS lookup failed for {}: {}", viewName, e.getMessage());
            return cols;
        }
        if (cols.isEmpty()) {
            warnings.add(viewName + ": view not found or no SELECT privilege "
                    + "(missing privilege, or APEX not installed / different version) — section skipped.");
        }
        return cols;
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
        return executeQuery(conn, sql, warnings, "APEX_APPLICATIONS", params, 0);
    }

    // -------------------------------------------------------------------------
    // Generic query executor
    // -------------------------------------------------------------------------

    /**
     * Runs a parameterized SQL query and returns results as a list of ordered maps.
     *
     * Column names are lowercased for idiomatic JSON keys. CLOB values (APEX source
     * columns are frequently CLOBs) are materialized to String and optionally truncated
     * to {@code maxCodeLen}. Temporal types are normalized to ISO-8601 strings.
     *
     * Failures (most commonly ORA-00942 / ORA-00904) are added to warnings and return
     * an empty list — allowing other sections to still populate.
     *
     * @param conn       active JDBC connection
     * @param sql        parameterized SQL with ? placeholders
     * @param warnings   mutable list for error messages
     * @param viewName   human-readable name for error messages
     * @param params     bind parameters in positional order
     * @param maxCodeLen truncate String/CLOB values to this length (0 = unlimited)
     * @return list of row maps (lowercased column → value), empty on error
     */
    private List<Map<String, Object>> executeQuery(Connection conn, String sql,
            List<String> warnings, String viewName, Object[] params, int maxCodeLen) {
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
                        row.put(cols.get(i - 1), readValue(rs, i, maxCodeLen));
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

    /**
     * Reads a single column value and normalizes it for JSON.
     *
     * CLOBs are streamed into a String (and truncated if maxCodeLen &gt; 0), since APEX
     * stores most source columns as CLOB. Strings are likewise truncated when a cap is
     * set. Timestamps/dates become ISO-8601 strings; numbers are preserved.
     *
     * @param rs         the result set positioned on a row
     * @param i          1-based column index
     * @param maxCodeLen truncation cap for String/CLOB values (0 = unlimited)
     * @return the normalized value (String, Number, Boolean, or null)
     * @throws SQLException if the value cannot be read
     */
    private Object readValue(ResultSet rs, int i, int maxCodeLen) throws SQLException {
        Object val = rs.getObject(i);
        if (val == null) {
            return null;
        }
        if (val instanceof Clob clob) {
            long len = clob.length();
            int take = (maxCodeLen > 0) ? (int) Math.min(len, maxCodeLen) : (int) Math.min(len, Integer.MAX_VALUE);
            String s = take == 0 ? "" : clob.getSubString(1, take);
            return (maxCodeLen > 0 && len > maxCodeLen) ? s + truncationNote(len) : s;
        }
        if (val instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (val instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (val instanceof String s) {
            if (maxCodeLen > 0 && s.length() > maxCodeLen) {
                return s.substring(0, maxCodeLen) + truncationNote(s.length());
            }
            return s;
        }
        return val;
    }

    /**
     * Builds the marker appended to a value truncated by max_code_length.
     *
     * @param originalLength the full length of the original value
     * @return a human-readable truncation marker
     */
    private String truncationNote(long originalLength) {
        return "\n-- [truncated by max_code_length; original length " + originalLength + " chars] --";
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
     * Extracts a String argument from the tool call arguments map.
     *
     * @param args tool call arguments map
     * @param key  argument name
     * @return the trimmed string value, or null if absent/blank
     */
    private String getStringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Extracts a list-of-strings argument from the tool call arguments map.
     *
     * Accepts a JSON array (most clients) or a single comma-separated string
     * (defensive). Returns null when absent so the caller can default to "all".
     *
     * @param args tool call arguments map
     * @param key  argument name
     * @return list of string values, or null if absent
     */
    @SuppressWarnings("unchecked")
    private List<String> getStringListArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        // Defensive: a single comma-separated string
        String s = val.toString().trim();
        if (s.isEmpty()) return null;
        return Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toList());
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
