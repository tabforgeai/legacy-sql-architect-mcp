package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.github.tabforgeai.legacysqlarchitect.model.ProcedureInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.function.BiFunction;

/**
 * MCP tool: get_procedure_source
 *
 * Retrieves the source code of one or all stored procedures/functions in a schema.
 * The raw SQL text is passed to the AI agent, which then interprets the business
 * logic. This tool does NOT summarize or analyze the code - it is a retrieval tool.
 *
 * This separation of concerns is intentional: the AI agent is better equipped to
 * understand business rules from raw SQL than any static analysis approach.
 * The server's role is to fetch and deliver the source accurately.
 *
 * Source code retrieval is database-specific:
 *   PostgreSQL:  pg_get_functiondef(oid) via pg_proc / pg_namespace
 *                This returns the complete "CREATE OR REPLACE FUNCTION ..." statement,
 *                which is more complete than information_schema.routine_definition
 *                (which can be truncated for large procedures).
 *   SQL Server:  sys.sql_modules.definition joined with sys.objects
 *                Returns the full procedure definition including parameters.
 *
 * Tool input parameters:
 *   - procedure_name  (optional) Name of a specific procedure/function to retrieve.
 *                     If omitted, all procedures in the schema are returned.
 *   - schema          (optional) Database schema. Defaults to config.json db_schema.
 *
 * Tool output:
 *   JSON array of procedure objects. Example:
 * <pre>
 * [
 *   {
 *     "name": "calculate_discount",
 *     "schema": "public",
 *     "type": "FUNCTION",
 *     "language": "plpgsql",
 *     "source_code": "CREATE OR REPLACE FUNCTION calculate_discount(...)\n RETURNS numeric\n LANGUAGE plpgsql\nAS $function$\nBEGIN\n  IF customer_tier = 'VIP' THEN\n    RETURN 0.15;\n  ELSE\n    RETURN 0.05;\n  END IF;\nEND;\n$function$"
 *   }
 * ]
 * </pre>
 *
 * Downstream usage - this tool's output is consumed by:
 *   - AI agent directly: interprets the source code to extract business rules
 *     (e.g., "VIP customers get 15% discount, others get 5%")
 *   - AI agent + data_sampler: combined usage to cross-reference logic against real data
 *     (e.g., identify why orders are stuck by reading procedure logic + sampling data)
 *   - AI agent + dependency_graph: after seeing which procedure a trigger calls,
 *     the agent fetches that procedure's source to understand the full execution flow
 */
public class GetProcedureSource implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(GetProcedureSource.class);

    public static final String TOOL_NAME = "get_procedure_source";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final JdbcClient jdbcClient;

    /**
     * Creates a new GetProcedureSource tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public GetProcedureSource(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for get_procedure_source.
     *
     * @param jsonMapper the MCP SDK's JSON mapper for parsing the input schema string
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "procedure_name": {
                      "type": "string",
                      "description": "Name of the stored procedure or function to retrieve. If omitted, all procedures in the schema are returned."
                    },
                    "schema": {
                      "type": "string",
                      "description": "Database schema name. Defaults to the schema in config.json."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Retrieves the source code of stored procedures and functions from the database. \
                        Pass a procedure_name to retrieve one specific procedure, or omit it to retrieve \
                        all procedures in the schema. Use this to understand business logic that is \
                        implemented inside the database rather than in application code.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the get_procedure_source tool when called by the AI agent.
     *
     * Delegates to the appropriate database-specific retrieval method based
     * on the db_type configured in config.json.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request with optional procedure_name and schema
     * @return a CallToolResult containing procedure source code as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        String schema        = resolveSchema(args);
        String procedureName = args != null ? (String) args.get("procedure_name") : null;

        log.info("Tool '{}' called: schema={}, procedure={}", TOOL_NAME, schema, procedureName != null ? procedureName : "(all)");

        try {
            List<ProcedureInfo> procedures;
            String dbType = jdbcClient.getConfig().getDbType();

            if ("sqlserver".equalsIgnoreCase(dbType)) {
                procedures = fetchFromSqlServer(schema, procedureName);
            } else if ("oracle".equalsIgnoreCase(dbType)) {
                procedures = fetchFromOracle(schema, procedureName);
            } else {
                // Default to PostgreSQL
                procedures = fetchFromPostgres(schema, procedureName);
            }

            if (procedures.isEmpty()) {
                String msg = procedureName != null
                        ? "No procedure or function named '" + procedureName + "' found in schema '" + schema + "'."
                        : "No procedures or functions found in schema '" + schema + "'.";
                return McpSchema.CallToolResult.builder().addTextContent(msg).build();
            }

            log.info("get_procedure_source returning {} procedure(s)", procedures.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(procedures))
                    .build();

        } catch (Exception e) {
            log.error("get_procedure_source failed: {}", e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error retrieving procedure source: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * Retrieves procedure source code from PostgreSQL using pg_get_functiondef().
     *
     * pg_get_functiondef() is preferred over information_schema.routine_definition
     * because it returns the COMPLETE "CREATE OR REPLACE FUNCTION ..." statement
     * including parameter names, types, and the full body, even for large procedures
     * that would otherwise be truncated in information_schema.
     *
     * The query joins pg_proc (procedure catalog) with pg_namespace (schema catalog)
     * and uses pg_get_functiondef(oid) to reconstruct the full source.
     * pg_language provides the language name (plpgsql, sql, etc.).
     *
     * When procedure_name is null, all non-system procedures in the schema are returned.
     * System procedures (those belonging to pg_catalog) are excluded automatically
     * by filtering on the schema name.
     *
     * @param schema        the PostgreSQL schema name
     * @param procedureName specific procedure name to fetch, or null for all
     * @return list of ProcedureInfo with source_code populated
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> fetchFromPostgres(String schema, String procedureName) throws SQLException {
        // Build the query dynamically based on whether a specific procedure is requested.
        // Using parameterized PreparedStatement to prevent SQL injection even though
        // this is a read-only developer tool.
        StringBuilder sql = new StringBuilder("""
                SELECT p.proname                         AS name,
                       n.nspname                         AS schema,
                       CASE WHEN p.prokind = 'f' THEN 'FUNCTION'
                            WHEN p.prokind = 'p' THEN 'PROCEDURE'
                            ELSE 'FUNCTION' END          AS type,
                       l.lanname                         AS language,
                       pg_get_functiondef(p.oid)         AS source_code
                FROM   pg_proc      p
                JOIN   pg_namespace n ON n.oid = p.pronamespace
                JOIN   pg_language  l ON l.oid = p.prolang
                WHERE  n.nspname = ?
                """);

        if (procedureName != null) {
            sql.append(" AND p.proname = ?");
        }
        sql.append(" ORDER BY p.proname");

        List<ProcedureInfo> result = new ArrayList<>();
        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql.toString())) {
            ps.setString(1, schema);
            if (procedureName != null) {
                ps.setString(2, procedureName);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProcedureInfo info = new ProcedureInfo(
                            rs.getString("name"),
                            rs.getString("schema"),
                            rs.getString("type")
                    );
                    info.setLanguage(rs.getString("language"));
                    info.setSourceCode(rs.getString("source_code"));
                    result.add(info);
                }
            }
        }
        return result;
    }

    /**
     * Retrieves procedure source code from SQL Server using sys.sql_modules.
     *
     * sys.sql_modules.definition contains the full T-SQL text of the procedure,
     * equivalent to what you would see in SSMS "Modify" view.
     *
     * The query joins:
     *   sys.sql_modules   → provides the definition text
     *   sys.objects       → provides the object name, schema, and type
     *
     * Object types filtered: P (stored procedure), FN (scalar function),
     * IF (inline table-valued function), TF (table-valued function).
     *
     * Note: Procedures with ENCRYPTION applied will have a NULL definition.
     * In that case, source_code is set to "(encrypted - source not available)".
     *
     * @param schema        the SQL Server schema name (e.g., "dbo")
     * @param procedureName specific procedure name to fetch, or null for all
     * @return list of ProcedureInfo with source_code populated
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> fetchFromSqlServer(String schema, String procedureName) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT o.name                                              AS name,
                       SCHEMA_NAME(o.schema_id)                           AS schema,
                       CASE o.type
                           WHEN 'P'  THEN 'PROCEDURE'
                           WHEN 'FN' THEN 'FUNCTION'
                           WHEN 'IF' THEN 'FUNCTION'
                           WHEN 'TF' THEN 'FUNCTION'
                           ELSE 'PROCEDURE' END                           AS type,
                       'TSQL'                                             AS language,
                       COALESCE(m.definition, '(encrypted - source not available)') AS source_code
                FROM   sys.sql_modules  m
                JOIN   sys.objects      o ON o.object_id = m.object_id
                WHERE  o.type IN ('P', 'FN', 'IF', 'TF')
                  AND  SCHEMA_NAME(o.schema_id) = ?
                """);

        if (procedureName != null) {
            sql.append(" AND o.name = ?");
        }
        sql.append(" ORDER BY o.name");

        List<ProcedureInfo> result = new ArrayList<>();
        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql.toString())) {
            ps.setString(1, schema);
            if (procedureName != null) {
                ps.setString(2, procedureName);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProcedureInfo info = new ProcedureInfo(
                            rs.getString("name"),
                            rs.getString("schema"),
                            rs.getString("type")
                    );
                    info.setLanguage(rs.getString("language"));
                    info.setSourceCode(rs.getString("source_code"));
                    result.add(info);
                }
            }
        }
        return result;
    }

    /**
     * Retrieves procedure/function/package source code from Oracle using ALL_SOURCE.
     *
     * ALL_SOURCE stores source code line-by-line (one row per line, ordered by LINE).
     * We query all lines without any SQL aggregation and reassemble the full source
     * in Java using a StringBuilder per object. This avoids ORA-01489 ("result of
     * string concatenation is too long"), which occurs when LISTAGG is used and the
     * total source of a package or large procedure exceeds Oracle's VARCHAR2 limit
     * (32767 bytes). By concatenating in Java, there is no length restriction.
     *
     * Objects are grouped by the composite key (TYPE, NAME) using a LinkedHashMap,
     * which preserves the ORDER BY TYPE, NAME, LINE ordering from the query.
     *
     * Schema comparison uses UPPER() because Oracle stores object names in uppercase
     * by default. Mixed-case input from config.json or the AI agent is normalized.
     *
     * @param schema        the Oracle schema (owner) name — compared case-insensitively
     * @param procedureName specific object name to fetch, or null for all objects
     * @return list of ProcedureInfo with source_code populated
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> fetchFromOracle(String schema, String procedureName)
            throws SQLException {

        // Query individual lines — no LISTAGG, no length limit.
        // Ordering by TYPE, NAME, LINE ensures that lines arrive in the correct
        // sequence for each object, which is required for in-order Java concatenation.
        StringBuilder sql = new StringBuilder("""
                SELECT s.NAME  AS name,
                       s.OWNER AS schema,
                       s.TYPE  AS type,
                       s.TEXT  AS line_text
                FROM   ALL_SOURCE s
                WHERE  s.OWNER = UPPER(?)
                  AND  s.TYPE IN ('PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY',
                                  'TRIGGER', 'TYPE', 'TYPE BODY')
                """);

        if (procedureName != null) {
            sql.append("  AND  s.NAME = UPPER(?)\n");
        }
        sql.append("ORDER BY s.TYPE, s.NAME, s.LINE");

        // LinkedHashMap preserves insertion order (TYPE, NAME ordering from the query).
        // Key = "TYPE\tNAME" — tab separator avoids false collisions if name contains a dot.
        java.util.LinkedHashMap<String, ProcedureInfo>    infoMap   = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, StringBuilder>    sourceMap = new java.util.LinkedHashMap<>();

        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql.toString())) {
            ps.setString(1, schema);
            if (procedureName != null) {
                ps.setString(2, procedureName);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name     = rs.getString("name");
                    String owner    = rs.getString("schema");
                    String type     = rs.getString("type");
                    String lineText = rs.getString("line_text");

                    String key = type + "\t" + name;

                    // First line for this object: create the ProcedureInfo and StringBuilder
                    if (!infoMap.containsKey(key)) {
                        ProcedureInfo info = new ProcedureInfo(name, owner, type);
                        info.setLanguage("PL/SQL");
                        infoMap.put(key, info);
                        sourceMap.put(key, new StringBuilder());
                    }

                    // Append this line (ALL_SOURCE TEXT already contains the newline)
                    if (lineText != null) {
                        sourceMap.get(key).append(lineText);
                    }
                }
            }
        }

        // Transfer accumulated source code from StringBuilders into ProcedureInfo objects
        List<ProcedureInfo> result = new ArrayList<>();
        for (String key : infoMap.keySet()) {
            ProcedureInfo info = infoMap.get(key);
            info.setSourceCode(sourceMap.get(key).toString());
            result.add(info);
        }
        return result;
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
