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
 * MCP tool: data_sampler
 *
 * Returns a small number of randomly selected rows from a database table.
 * This gives the AI agent real-world examples of data format and actual values,
 * which is critical for understanding legacy databases where column names like
 * "STATUS", "TYPE", or "FLAG" give no hint about the actual stored values
 * (e.g., whether STATUS is 0/1, 'A'/'I', or 'ACTIVE'/'INACTIVE').
 *
 * Sensitive columns are masked by default to protect personal data.
 * Masking replaces actual values with "***MASKED***" based on column name heuristics.
 * The masking flag can be overridden per call via the mask_sensitive parameter.
 *
 * Tool input parameters:
 *   - table          (required) Name of the table to sample.
 *   - schema         (optional) Database schema. Defaults to config.json db_schema.
 *   - row_count      (optional) Number of rows to return. Defaults to config.json
 *                    data_sampler_rows (default: 10). Maximum: 50.
 *   - mask_sensitive (optional) Boolean. If true, sensitive column values are masked.
 *                    Defaults to config.json data_sampler_mask_sensitive (default: true).
 *
 * Tool output:
 *   JSON object example (with masking enabled):
 * <pre>
 * {
 *   "table": "orders",
 *   "schema": "public",
 *   "row_count": 3,
 *   "rows": [
 *     { "id": 1, "customer_id": 42, "status": "OPEN",      "total": 150.00, "email": "***MASKED***" },
 *     { "id": 2, "customer_id": 17, "status": "CLOSED",    "total": 80.00,  "email": "***MASKED***" },
 *     { "id": 3, "customer_id": 5,  "status": "CANCELLED", "total": 0,      "email": "***MASKED***" }
 *   ]
 * }
 * </pre>
 *
 * Downstream usage - this tool's output is consumed by:
 *   - AI agent directly: to understand real data formats and enumerate actual enum values
 *     (e.g., discovering that "status" is 'OPEN'/'CLOSED'/'CANCELLED', not 0/1)
 *   - AI agent + business_rule reasoning: combined with get_procedure_source output,
 *     the agent can cross-reference procedure logic against real data patterns
 *     (e.g., "Why are some orders stuck in OPEN? → sample shows many PARTIAL_PAYMENT rows")
 *
 * Random sampling is implemented differently per database:
 *   - PostgreSQL:   SELECT * FROM schema.table ORDER BY RANDOM() LIMIT n
 *   - SQL Server:   SELECT TOP n * FROM schema.table ORDER BY NEWID()
 */
public class DataSampler implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(DataSampler.class);

    public static final String TOOL_NAME = "data_sampler";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Maximum number of rows the tool will ever return, regardless of the row_count
     * argument. This prevents accidentally overwhelming the AI agent's context window
     * with a huge dataset.
     */
    private static final int MAX_ROWS = 50;

    /**
     * Column name substrings that trigger masking when mask_sensitive=true.
     * Matching is case-insensitive and checks if the column name CONTAINS
     * any of these substrings.
     *
     * Example: "CUST_EMAIL_ADDR" matches "email" -> masked.
     */
    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "password", "passwd", "pwd",
            "email", "e_mail",
            "phone", "mobile", "tel",
            "ssn", "social_security",
            "credit_card", "card_number", "cvv",
            "iban", "account_number",
            "birth", "dob",
            "address", "zip", "postal",
            "token", "secret", "api_key"
    );

    private final JdbcClient jdbcClient;

    /**
     * Creates a new DataSampler tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public DataSampler(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for data_sampler.
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
                      "description": "Name of the table to sample rows from."
                    },
                    "schema": {
                      "type": "string",
                      "description": "Database schema name. Defaults to the schema in config.json."
                    },
                    "row_count": {
                      "type": "integer",
                      "description": "Number of random rows to return (1-50). Defaults to the value in config.json (typically 10)."
                    },
                    "mask_sensitive": {
                      "type": "boolean",
                      "description": "If true, values in columns with sensitive names (email, password, phone, etc.) are replaced with ***MASKED***. Defaults to the value in config.json (typically true)."
                    }
                  },
                  "required": ["table"]
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Returns a sample of random rows from a database table so the AI agent \
                        can understand the actual data format and real values stored in the database. \
                        Essential for legacy databases where column names are cryptic. \
                        Sensitive columns (email, password, phone, etc.) are masked by default.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the data_sampler tool when called by the AI agent.
     *
     * Builds and runs a database-specific random sample query, reads the result,
     * applies masking if requested, and returns the rows as a JSON object.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request containing the table name and options
     * @return a CallToolResult containing sampled rows as JSON, or an error message
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        // table is required - validate before proceeding
        if (args == null || args.get("table") == null) {
            return errorResult("Missing required parameter: 'table'");
        }

        String table  = (String) args.get("table");
        String schema = resolveSchema(args);
        int rowCount  = resolveRowCount(args);
        boolean mask  = resolveMaskSensitive(args);

        log.info("Tool '{}' called: schema={}, table={}, rows={}, mask={}", TOOL_NAME, schema, table, rowCount, mask);

        try {
            String sql = buildSampleQuery(schema, table, rowCount);
            List<Map<String, Object>> rows = executeSampleQuery(sql, mask);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("table", table);
            result.put("schema", schema);
            result.put("row_count", rows.size());
            result.put("rows", rows);

            log.info("data_sampler returned {} rows from {}.{}", rows.size(), schema, table);

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();

        } catch (Exception e) {
            log.error("data_sampler failed for table {}.{}: {}", schema, table, e.getMessage(), e);
            return errorResult("Error sampling table '" + schema + "." + table + "': " + e.getMessage());
        }
    }

    /**
     * Builds the random-sample SQL query appropriate for the configured database type.
     *
     * The fully-qualified table name (schema.table) is used to avoid ambiguity
     * when the database contains multiple schemas. The table and schema names are
     * quoted with double quotes to handle reserved words and mixed-case names.
     *
     * @param schema   the database schema name
     * @param table    the table name
     * @param rowCount the number of rows to return
     * @return a SQL string ready for execution
     */
    private String buildSampleQuery(String schema, String table, int rowCount) {
        // Build the fully-qualified, quoted table reference
        String qualifiedTable = "\"" + schema + "\".\"" + table + "\"";

        String dbType = jdbcClient.getConfig().getDbType();

        if ("sqlserver".equalsIgnoreCase(dbType)) {
            // SQL Server uses TOP syntax and NEWID() for random ordering
            return "SELECT TOP " + rowCount + " * FROM " + qualifiedTable + " ORDER BY NEWID()";
        } else {
            // PostgreSQL (default): RANDOM() function with LIMIT clause
            return "SELECT * FROM " + qualifiedTable + " ORDER BY RANDOM() LIMIT " + rowCount;
        }
    }

    /**
     * Executes the sample query and reads the result rows into a list of maps.
     *
     * Each row is represented as a LinkedHashMap (preserving column order)
     * from column name to value. NULL values are represented as Java null,
     * which Jackson serializes as JSON null.
     *
     * Sensitive column masking is applied per column based on name heuristics
     * (see SENSITIVE_KEYWORDS).
     *
     * @param sql  the sample query to execute
     * @param mask whether to apply sensitive column masking
     * @return list of row maps, one map per row
     * @throws SQLException if the query fails
     */
    private List<Map<String, Object>> executeSampleQuery(String sql, boolean mask) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Statement stmt = jdbcClient.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData rsMeta = rs.getMetaData();
            int columnCount = rsMeta.getColumnCount();

            // Build a list of column names once (not per row) for efficiency
            List<String> columnNames = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                columnNames.add(rsMeta.getColumnName(i));
            }

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String colName = columnNames.get(i - 1);
                    Object value = rs.getObject(i);

                    // Apply masking: if masking is enabled and the column name
                    // contains a sensitive keyword, replace the value
                    if (mask && value != null && isSensitiveColumn(colName)) {
                        value = "***MASKED***";
                    }
                    row.put(colName, value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Checks if a column name suggests it contains sensitive personal data.
     *
     * Matching is case-insensitive and checks for substring presence.
     * This is a best-effort heuristic - it will catch most common cases
     * (EMAIL, CUST_EMAIL_ADDR, USER_PASSWORD, etc.) but cannot be exhaustive.
     *
     * @param columnName the column name to check
     * @return true if the column name matches any sensitive keyword
     */
    private boolean isSensitiveColumn(String columnName) {
        String lower = columnName.toLowerCase();
        return SENSITIVE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Resolves the schema name from request arguments or config default.
     *
     * @param args the tool call arguments map
     * @return the schema name to use
     */
    private String resolveSchema(Map<String, Object> args) {
        if (args != null && args.get("schema") instanceof String s && !s.isBlank()) {
            return s;
        }
        return jdbcClient.getConfig().getDbSchema();
    }

    /**
     * Resolves the row count from request arguments, config default, or hard default.
     * Clamps the value to the MAX_ROWS limit.
     *
     * @param args the tool call arguments map
     * @return the number of rows to sample, clamped to [1, MAX_ROWS]
     */
    private int resolveRowCount(Map<String, Object> args) {
        int count = jdbcClient.getConfig().getDataSamplerRows();
        if (args != null && args.get("row_count") instanceof Number n) {
            count = n.intValue();
        }
        return Math.max(1, Math.min(count, MAX_ROWS));
    }

    /**
     * Resolves the mask_sensitive flag from request arguments or config default.
     *
     * @param args the tool call arguments map
     * @return true if sensitive columns should be masked
     */
    private boolean resolveMaskSensitive(Map<String, Object> args) {
        if (args != null && args.get("mask_sensitive") instanceof Boolean b) {
            return b;
        }
        return jdbcClient.getConfig().isDataSamplerMaskSensitive();
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
