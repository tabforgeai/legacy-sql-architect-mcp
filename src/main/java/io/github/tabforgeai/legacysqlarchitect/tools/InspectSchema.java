package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.github.tabforgeai.legacysqlarchitect.db.MetadataReader;
import io.github.tabforgeai.legacysqlarchitect.model.TableInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP tool: inspect_schema
 *
 * Returns the complete structural metadata of the target database schema:
 * tables, columns (with types, PK/FK flags, nullability), indexes, and comments.
 *
 * This is the foundational tool that the AI agent should call first when
 * starting to work with an unknown legacy database. The output gives the agent
 * a full structural map of the database without requiring any prior knowledge.
 *
 * Tool input parameters:
 *   - schema        (optional) Database schema name to inspect.
 *                   Defaults to the schema defined in config.json.
 *   - table_filter  (optional) SQL LIKE pattern to filter tables by name.
 *                   Example: "ORD%" returns only tables starting with "ORD".
 *                   Defaults to "%" (all tables).
 *
 * Tool output:
 *   JSON array of table objects. Abbreviated example:
 * <pre>
 * [
 *   {
 *     "name": "orders",
 *     "schema": "public",
 *     "comment": "Customer order header records",
 *     "columns": [
 *       { "name": "id",          "type": "bigint",    "primaryKey": true,  "nullable": false },
 *       { "name": "customer_id", "type": "bigint",    "primaryKey": false, "nullable": false,
 *         "foreignKeyTable": "customers", "foreignKeyColumn": "id" },
 *       { "name": "status",      "type": "varchar",   "primaryKey": false, "nullable": true,
 *         "comment": null }
 *     ],
 *     "indexes": ["idx_orders_customer", "idx_orders_status"]
 *   }
 * ]
 * </pre>
 *
 * Downstream usage - this tool's output is consumed by:
 *   - AI agent directly: to answer "what tables exist and how are they related?"
 *   - generate_mermaid_erd: reads FK relationships to build the ERD diagram
 *   - generate_documentation: uses table/column metadata as the documentation source
 *   - generate_java_dao: uses column types and PK/FK info to generate Entity and Repository code
 *
 * This class implements BiFunction so it can be passed directly to
 * McpServer builder's .toolCall(definition, handler) method.
 */
public class InspectSchema implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(InspectSchema.class);

    /** Tool name as registered with the MCP server and visible to the AI agent. */
    public static final String TOOL_NAME = "inspect_schema";

    /**
     * Jackson ObjectMapper for serializing the result to JSON.
     * Pretty-print is enabled so the AI agent receives readable, structured output.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Shared database client providing the JDBC connection and config. */
    private final JdbcClient jdbcClient;

    /**
     * Creates a new InspectSchema tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public InspectSchema(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for inspect_schema.
     *
     * The tool definition includes the name, description, and JSON Schema for
     * the input parameters. This is what the MCP client (Claude, Cursor) shows
     * the AI agent when it lists available tools.
     *
     * The input schema is defined as a raw JSON string and parsed by the MCP SDK.
     * This is the recommended approach for inline schema definitions.
     *
     * @param jsonMapper the MCP SDK's JSON mapper, required by Tool.Builder to parse
     *                   the inputSchema string into the internal JsonSchema representation
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "schema": {
                      "type": "string",
                      "description": "Database schema name to inspect (e.g., 'public' for PostgreSQL, 'dbo' for SQL Server). If omitted, uses the default schema from config.json."
                    },
                    "table_filter": {
                      "type": "string",
                      "description": "Optional SQL LIKE pattern to filter table names (e.g., 'ORD%' returns only tables starting with ORD). If omitted, all tables are returned."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Extracts complete structural metadata from the database schema: \
                        tables, columns (with data types, primary keys, foreign keys, \
                        nullability), indexes, and comments. \
                        Use this as the first step when analyzing an unknown legacy database. \
                        Returns a JSON array of table objects with full column details.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the inspect_schema tool when called by the AI agent.
     *
     * Extracts the optional input parameters from the request, reads the schema
     * metadata via MetadataReader, serializes the result to JSON, and returns
     * it as a text content response.
     *
     * On error, returns an error result with isError=true so the AI agent
     * can report the problem clearly rather than receiving an empty response.
     *
     * @param exchange the MCP server exchange context (not used by this tool,
     *                 but required by the BiFunction contract)
     * @param request  the tool call request containing optional input arguments
     * @return a CallToolResult containing the JSON schema output, or an error message
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        // Extract optional input parameters from the request arguments map
        Map<String, Object> args = request.arguments();

        String schema = resolveSchema(args);
        String tableFilter = args != null ? (String) args.get("table_filter") : null;

        log.info("Tool '{}' called: schema={}, table_filter={}", TOOL_NAME, schema, tableFilter);

        try {
            MetadataReader reader = new MetadataReader(jdbcClient.getConnection());
            List<TableInfo> tables = reader.readTables(schema, tableFilter);

            // Serialize the result to pretty-printed JSON for the AI agent
            String json = JSON.writeValueAsString(tables);

            log.info("inspect_schema returned {} tables", tables.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .build();

        } catch (Exception e) {
            log.error("inspect_schema failed: {}", e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error executing inspect_schema: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * Resolves the schema name to use for the metadata query.
     *
     * Resolution order:
     *   1. "schema" argument from the AI agent's tool call (explicit override)
     *   2. db_schema from config.json (default for this database)
     *
     * @param args the arguments map from the tool call request (may be null)
     * @return the schema name to pass to MetadataReader.readTables()
     */
    private String resolveSchema(Map<String, Object> args) {
        if (args != null && args.get("schema") instanceof String s && !s.isBlank()) {
            return s;
        }
        return jdbcClient.getConfig().getDbSchema();
    }
}
