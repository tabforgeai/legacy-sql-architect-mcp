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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP tool: apex_explain_batch
 *
 * Runs EXPLAIN PLAN over every SQL statement embedded in an APEX application's
 * components — report/LOV/validation queries — and reports each statement's optimizer cost,
 * estimated row count, and structural red flags (full table scans, Cartesian joins). It
 * turns "here are 200 embedded queries" into "these 6 have a full table scan" without
 * running any of them.
 *
 * What it explains:
 *   - SQL regions (APEX_APPLICATION_PAGE_REGIONS.REGION_SOURCE where QUERY_TYPE_CODE='SQL')
 *   - SQL LOVs (APEX_APPLICATION_LOVS.LIST_OF_VALUES_QUERY)
 *   - SQL validations (APEX_APPLICATION_PAGE_VAL, "Exists"/"NOT Exists" types — their
 *     VALIDATION_EXPRESSION1 is a SELECT). PL/SQL processes/validations are NOT explained;
 *     EXPLAIN PLAN only accepts a single SQL statement, not a PL/SQL block.
 *
 * Handling APEX-isms:
 *   - Bind variables (:P1_X, :FLOW_SECURITY_GROUP_ID, ...) need no definition — EXPLAIN
 *     PLAN treats them as untyped placeholders.
 *   - APEX substitution strings (&ITEM. , &APP_ID.) are not valid SQL outside a literal, so
 *     they are replaced with a bind placeholder (:APEX_SUBST) before explaining. This keeps
 *     the statement parseable; the resulting plan is a best-effort estimate.
 *   - A statement that still fails to parse (dynamic SQL, PL/SQL, unusual syntax) is
 *     reported with status "error" and its Oracle message — never aborting the batch.
 *
 * EXPLAIN PLAN writes to the session PLAN_TABLE (a scratch table; no business data is
 * touched). Because the server holds a read-only JDBC connection, this tool transiently
 * clears the read-only flag for the duration of the batch and restores it in a finally
 * block. The server processes one request at a time, so this is safe. Each statement uses a
 * unique STATEMENT_ID that is deleted from PLAN_TABLE after its plan is read.
 *
 * Tool input parameters:
 *   - app_id          (required for analysis) APEX application ID.
 *   - page_id         (optional) restrict region/validation statements to one page.
 *   - component_types (optional) subset of ["regions","lovs","validations"]; default all.
 *   - max_statements  (optional, default 100) safety cap on how many statements to explain.
 *
 * Tool output (example, app_id=100):
 * <pre>
 * {
 *   "privilege_warnings": [],
 *   "applications": [ { "application_id": 100, "application_name": "HR Portal" } ],
 *   "summary": { "explained": 87, "errors": 3, "with_red_flags": 6 },
 *   "statements": [
 *     { "component_type": "region", "page_id": 12, "name": "Orders", "status": "ok",
 *       "plan_cost": 18422, "plan_rows": 1, "red_flags": ["TABLE ACCESS FULL on ORDERS"],
 *       "sql_text": "select ..." }
 *   ],
 *   "recommendations": [
 *     { "priority": "HIGH", "category": "EXPLAIN_RED_FLAG", "page_id": 12, "name": "Orders",
 *       "message": "Region 'Orders' (page 12) plan has: TABLE ACCESS FULL on ORDERS. Cost 18422. "
 *                  + "Read its SQL with get_apex_source and tune with query_plan_expert." }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage:
 *   - The red-flag statements are the shortlist to hand to query_plan_expert for the full
 *     plan and index advice, and to get_apex_source to read/patch the component's code.
 */
public class ApexExplainBatch
        implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(ApexExplainBatch.class);

    public static final String TOOL_NAME = "apex_explain_batch";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** APEX substitution token, e.g. &APP_ID. or &P1_ITEM — replaced with a bind before EXPLAIN. */
    private static final Pattern SUBST_TOKEN = Pattern.compile("&[A-Za-z0-9_$#]+\\.?");

    /** Default and hard cap on how many statements to explain in one call. */
    private static final int DEFAULT_MAX_STATEMENTS = 100;

    /** Plan cost at/above this makes a red-flag finding HIGH (else MEDIUM). */
    private static final long COST_HIGH = 10_000;

    private final JdbcClient jdbcClient;

    /**
     * Creates a new ApexExplainBatch tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected to an Oracle DB
     */
    public ApexExplainBatch(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for apex_explain_batch.
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
                      "description": "APEX application ID whose embedded SQL to EXPLAIN. If omitted, applications are listed but nothing is explained."
                    },
                    "page_id": {
                      "type": "integer",
                      "description": "Restrict region and validation statements to a specific page ID."
                    },
                    "component_types": {
                      "type": "array",
                      "items": { "type": "string", "enum": ["regions", "lovs", "validations"] },
                      "description": "Which SQL sources to explain. Default: all three."
                    },
                    "max_statements": {
                      "type": "integer",
                      "description": "Safety cap on the number of statements to explain. Default: 100.",
                      "default": 100
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Runs EXPLAIN PLAN over an APEX application's embedded SQL (report/LOV/\
                        validation queries) and reports each statement's optimizer cost, row \
                        estimate, and structural red flags (full table scans, Cartesian \
                        joins) — without executing the queries. Bind variables are handled \
                        automatically and APEX substitution strings are neutralized so the \
                        SQL parses. Writes only to the session PLAN_TABLE. Use it to shortlist \
                        which components to hand to query_plan_expert. Oracle only.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the apex_explain_batch tool when called by the AI agent.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the analysis parameters
     * @return a CallToolResult containing the batch EXPLAIN results as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        String dbType = jdbcClient.getConfig().getDbType();
        if (!"oracle".equalsIgnoreCase(dbType)) {
            return errorResult("apex_explain_batch requires an Oracle database. "
                    + "Current db_type is '" + dbType + "'. Oracle APEX only runs on Oracle.");
        }

        Integer appId  = getIntArg(args, "app_id", null);
        Integer pageId = getIntArg(args, "page_id", null);
        int maxStmts   = getIntArg(args, "max_statements", DEFAULT_MAX_STATEMENTS);
        Set<String> types = getStringSet(args, "component_types",
                Set.of("regions", "lovs", "validations"));

        log.info("Tool '{}' called: app_id={}, page_id={}, types={}, max_statements={}",
                TOOL_NAME, appId, pageId, types, maxStmts);

        try {
            Connection conn = jdbcClient.getConnection();
            List<String> warnings = new ArrayList<>();

            List<Map<String, Object>> apps = queryApplications(conn, appId, warnings);
            if (apps.isEmpty() && appId != null) {
                return errorResult("Application " + appId + " not found in APEX_APPLICATIONS. "
                        + "Verify the application ID and SELECT privilege on APEX_APPLICATIONS.");
            }

            List<Statement2Explain> toExplain = new ArrayList<>();
            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> recommendations = List.of();

            if (appId != null && !apps.isEmpty()) {
                if (types.contains("regions"))     collectRegions(conn, appId, pageId, warnings, toExplain);
                if (types.contains("lovs"))        collectLovs(conn, appId, warnings, toExplain);
                if (types.contains("validations")) collectValidations(conn, appId, pageId, warnings, toExplain);

                boolean capped = toExplain.size() > maxStmts;
                if (capped) {
                    warnings.add("Collected " + toExplain.size() + " statements; explaining the first "
                            + maxStmts + " (raise max_statements to cover more).");
                    toExplain = toExplain.subList(0, maxStmts);
                }

                results = explainAll(conn, toExplain, warnings);
                recommendations = generateRecommendations(results);
            } else if (!apps.isEmpty()) {
                String appList = apps.stream()
                        .map(a -> a.get("application_id") + " (" + a.get("application_name") + ")")
                        .collect(Collectors.joining(", "));
                warnings.add("Set app_id to EXPLAIN embedded SQL. Available applications: " + appList);
            }

            ObjectNode result = JSON.createObjectNode();
            result.set("privilege_warnings", toStringArray(warnings));
            result.set("applications", toJsonArray(apps));
            result.set("summary", buildSummary(results));
            result.set("statements", toJsonArray(results));
            result.set("recommendations", toJsonArray(recommendations));

            log.info("apex_explain_batch: {} app(s), {} statement(s) explained, {} recommendation(s), "
                    + "{} warning(s)", apps.size(), results.size(), recommendations.size(), warnings.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("apex_explain_batch failed: {}", e.getMessage(), e);
            return errorResult("Error running APEX EXPLAIN batch: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Collection of SQL statements to explain
    // -------------------------------------------------------------------------

    /** A single embedded SQL statement to be explained, with its provenance. */
    private record Statement2Explain(String componentType, Object pageId, String name, String sql) {}

    /**
     * Collects SQL region sources (QUERY_TYPE_CODE='SQL') for the application/page.
     *
     * @param conn      active JDBC connection
     * @param appId     application ID
     * @param pageId    optional page filter
     * @param warnings  mutable list for error messages
     * @param out       accumulator to append collected statements to
     */
    private void collectRegions(Connection conn, int appId, Integer pageId, List<String> warnings,
            List<Statement2Explain> out) {
        String sql = "SELECT PAGE_ID, REGION_NAME, REGION_SOURCE "
                   + "FROM APEX_APPLICATION_PAGE_REGIONS "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND QUERY_TYPE_CODE = 'SQL' AND REGION_SOURCE IS NOT NULL "
                   + "ORDER BY PAGE_ID, REGION_NAME";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        for (Map<String, Object> r : executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_REGIONS", params)) {
            out.add(new Statement2Explain("region", r.get("page_id"),
                    str(r.get("region_name")), str(r.get("region_source"))));
        }
    }

    /**
     * Collects SQL LOV queries for the application.
     *
     * @param conn     active JDBC connection
     * @param appId    application ID
     * @param warnings mutable list for error messages
     * @param out      accumulator to append collected statements to
     */
    private void collectLovs(Connection conn, int appId, List<String> warnings,
            List<Statement2Explain> out) {
        String sql = "SELECT LIST_OF_VALUES_NAME, LIST_OF_VALUES_QUERY "
                   + "FROM APEX_APPLICATION_LOVS "
                   + "WHERE APPLICATION_ID = ? AND LIST_OF_VALUES_QUERY IS NOT NULL "
                   + "ORDER BY LIST_OF_VALUES_NAME";
        for (Map<String, Object> r : executeQuery(conn, sql, warnings, "APEX_APPLICATION_LOVS", new Object[]{appId})) {
            out.add(new Statement2Explain("lov", null,
                    str(r.get("list_of_values_name")), str(r.get("list_of_values_query"))));
        }
    }

    /**
     * Collects SQL validation queries ("Exists"/"NOT Exists" types) for the application/page.
     *
     * @param conn     active JDBC connection
     * @param appId    application ID
     * @param pageId   optional page filter
     * @param warnings mutable list for error messages
     * @param out      accumulator to append collected statements to
     */
    private void collectValidations(Connection conn, int appId, Integer pageId, List<String> warnings,
            List<Statement2Explain> out) {
        String sql = "SELECT PAGE_ID, VALIDATION_NAME, VALIDATION_EXPRESSION1 "
                   + "FROM APEX_APPLICATION_PAGE_VAL "
                   + "WHERE APPLICATION_ID = ? "
                   + (pageId != null ? "AND PAGE_ID = ? " : "")
                   + "AND VALIDATION_TYPE LIKE '%Exists%' AND VALIDATION_EXPRESSION1 IS NOT NULL "
                   + "ORDER BY PAGE_ID, VALIDATION_NAME";
        Object[] params = pageId != null ? new Object[]{appId, pageId} : new Object[]{appId};
        for (Map<String, Object> r : executeQuery(conn, sql, warnings, "APEX_APPLICATION_PAGE_VAL", params)) {
            out.add(new Statement2Explain("validation", r.get("page_id"),
                    str(r.get("validation_name")), str(r.get("validation_expression1"))));
        }
    }

    // -------------------------------------------------------------------------
    // EXPLAIN execution
    // -------------------------------------------------------------------------

    /**
     * Explains every collected statement, transiently clearing the connection's read-only
     * flag (EXPLAIN PLAN must write to PLAN_TABLE) and restoring it afterward.
     *
     * @param conn      active JDBC connection
     * @param toExplain the statements to explain
     * @param warnings  mutable list for error messages
     * @return per-statement result records (status ok/error, cost, red flags)
     */
    private List<Map<String, Object>> explainAll(Connection conn, List<Statement2Explain> toExplain,
            List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean wasReadOnly = false;
        try {
            wasReadOnly = conn.isReadOnly();
            if (wasReadOnly) conn.setReadOnly(false);
        } catch (SQLException e) {
            log.warn("Could not read/clear read-only flag: {}", e.getMessage());
        }
        try {
            int idx = 0;
            for (Statement2Explain s : toExplain) {
                out.add(explainOne(conn, s, "AEB" + (System.nanoTime() % 1_000_000L) + "_" + (idx++)));
            }
        } finally {
            if (wasReadOnly) {
                try { conn.setReadOnly(true); }
                catch (SQLException e) { log.warn("Could not restore read-only flag: {}", e.getMessage()); }
            }
        }
        return out;
    }

    /**
     * Explains a single statement and reads back its plan cost, row estimate, and red flags.
     *
     * @param conn        active JDBC connection
     * @param s           the statement to explain
     * @param statementId a unique PLAN_TABLE STATEMENT_ID for this statement
     * @return a result record with status "ok" (plus cost/rows/red_flags) or "error" (message)
     */
    private Map<String, Object> explainOne(Connection conn, Statement2Explain s, String statementId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("component_type", s.componentType());
        row.put("page_id",        s.pageId());
        row.put("name",           s.name());

        String sanitized = sanitize(s.sql());
        if (sanitized.isBlank()) {
            row.put("status", "error");
            row.put("error", "empty SQL after sanitization");
            return row;
        }

        try (Statement st = conn.createStatement()) {
            st.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + sanitized);
        } catch (SQLException e) {
            row.put("status", "error");
            row.put("error", firstLine(e.getMessage()));
            row.put("sql_text", abbreviate(sanitized, 400));
            return row;
        }

        try {
            // Root row (id=0) carries the overall cost and cardinality
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cost, cardinality FROM plan_table WHERE statement_id = ? AND id = 0")) {
                ps.setString(1, statementId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        row.put("plan_cost", rs.getObject("cost"));
                        row.put("plan_rows", rs.getObject("cardinality"));
                    }
                }
            }
            // Structural red flags
            List<String> redFlags = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT operation, options, object_name FROM plan_table "
                    + "WHERE statement_id = ? AND ("
                    + "  (operation = 'TABLE ACCESS' AND options = 'FULL') "
                    + "  OR options LIKE '%CARTESIAN%' "
                    + "  OR (operation = 'INDEX' AND options LIKE 'FULL SCAN%')) "
                    + "ORDER BY id")) {
                ps.setString(1, statementId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String op   = rs.getString("operation");
                        String opts = rs.getString("options");
                        String obj  = rs.getString("object_name");
                        redFlags.add((op + " " + opts).trim() + (obj != null ? " on " + obj : ""));
                    }
                }
            }
            row.put("status", "ok");
            row.put("red_flags", redFlags);
            row.put("sql_text", abbreviate(sanitized, 400));
        } catch (SQLException e) {
            row.put("status", "error");
            row.put("error", "plan read failed: " + firstLine(e.getMessage()));
        } finally {
            try (Statement del = conn.createStatement()) {
                del.execute("DELETE FROM plan_table WHERE statement_id = '" + statementId + "'");
            } catch (SQLException ignore) { /* best-effort cleanup */ }
        }
        return row;
    }

    /**
     * Prepares an APEX SQL source for EXPLAIN PLAN: strips a trailing semicolon and replaces
     * APEX substitution tokens (&ITEM.) with a bind placeholder so the statement parses.
     * Bind variables (:X) are left untouched — EXPLAIN accepts them undefined.
     *
     * @param sql the raw region/LOV/validation SQL (may be null)
     * @return the sanitized SQL, or "" if null/blank
     */
    private String sanitize(String sql) {
        if (sql == null) return "";
        String out = sql.trim();
        while (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
        out = SUBST_TOKEN.matcher(out).replaceAll(":APEX_SUBST");
        return out;
    }

    // -------------------------------------------------------------------------
    // Recommendations engine
    // -------------------------------------------------------------------------

    /**
     * Produces an EXPLAIN_RED_FLAG recommendation for each statement whose plan has a
     * structural red flag: HIGH when plan cost is at/above {@link #COST_HIGH}, else MEDIUM.
     *
     * @param results per-statement EXPLAIN results from {@link #explainAll}
     * @return recommendations sorted HIGH → MEDIUM
     */
    private List<Map<String, Object>> generateRecommendations(List<Map<String, Object>> results) {
        List<Map<String, Object>> recs = new ArrayList<>();
        for (Map<String, Object> r : results) {
            Object rf = r.get("red_flags");
            if (!(rf instanceof List<?> flags) || flags.isEmpty()) continue;

            long cost = r.get("plan_cost") instanceof Number n ? n.longValue() : 0;
            String flagText = flags.stream().map(String::valueOf).collect(Collectors.joining("; "));
            String label = labelFor(r);

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("priority",  cost >= COST_HIGH ? "HIGH" : "MEDIUM");
            rec.put("category",  "EXPLAIN_RED_FLAG");
            rec.put("page_id",   r.get("page_id"));
            rec.put("name",      r.get("name"));
            rec.put("plan_cost", r.get("plan_cost"));
            rec.put("red_flags", flags);
            rec.put("message", label + " plan has: " + flagText + ". Cost " + cost
                    + ". Read its SQL with get_apex_source and tune with query_plan_expert "
                    + "(check for missing indexes on the full-scanned tables).");
            recs.add(rec);
        }
        recs.sort(Comparator.comparingInt(r -> "HIGH".equals(r.get("priority")) ? 0 : 1));
        return recs;
    }

    /**
     * Builds a human-readable label for a statement result, e.g. "Region 'Orders' (page 12)".
     *
     * @param r a statement result record
     * @return a label describing the component
     */
    private String labelFor(Map<String, Object> r) {
        String type = str(r.get("component_type"));
        String cap = type.isEmpty() ? "Statement" : Character.toUpperCase(type.charAt(0)) + type.substring(1);
        String name = str(r.get("name"));
        Object page = r.get("page_id");
        return cap + " '" + name + "'" + (page != null ? " (page " + page + ")" : "");
    }

    /**
     * Builds the summary object: counts of explained, errored, and red-flagged statements.
     *
     * @param results per-statement EXPLAIN results
     * @return an ObjectNode with explained/errors/with_red_flags counts
     */
    private ObjectNode buildSummary(List<Map<String, Object>> results) {
        int explained = 0, errors = 0, redFlags = 0;
        for (Map<String, Object> r : results) {
            if ("ok".equals(r.get("status"))) {
                explained++;
                if (r.get("red_flags") instanceof List<?> f && !f.isEmpty()) redFlags++;
            } else {
                errors++;
            }
        }
        ObjectNode s = JSON.createObjectNode();
        s.put("explained", explained);
        s.put("errors", errors);
        s.put("with_red_flags", redFlags);
        return s;
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

    // -------------------------------------------------------------------------
    // Generic query executor
    // -------------------------------------------------------------------------

    /**
     * Runs a parameterized SQL query and returns results as a list of ordered maps.
     *
     * Column names are lowercased for idiomatic JSON keys. CLOB values (region/LOV/validation
     * source columns) are materialized to String. Failures are recorded in warnings and
     * return an empty list.
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
     * Lists are rendered as string arrays (used for red_flags).
     *
     * @param node  the target ObjectNode
     * @param key   the field name
     * @param value null, String, Number, Boolean, List, or any other Object
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
        else if (value instanceof List<?> list) {
            ArrayNode arr = node.putArray(key);
            for (Object o : list) arr.add(String.valueOf(o));
        } else                                  node.put(key, value.toString());
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
     * Extracts a set of strings from an array-valued argument, falling back to a default set.
     *
     * @param args        tool call arguments map
     * @param key         argument name
     * @param defaultsSet fallback when key is absent or not a non-empty list
     * @return a set of lowercased string values
     */
    private Set<String> getStringSet(Map<String, Object> args, String key, Set<String> defaultsSet) {
        Object val = args.get(key);
        if (val instanceof List<?> list && !list.isEmpty()) {
            Set<String> out = new LinkedHashSet<>();
            for (Object o : list) if (o != null) out.add(o.toString().toLowerCase());
            return out.isEmpty() ? defaultsSet : out;
        }
        return defaultsSet;
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
