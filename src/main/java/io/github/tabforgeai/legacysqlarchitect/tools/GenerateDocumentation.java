package io.github.tabforgeai.legacysqlarchitect.tools;

import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.github.tabforgeai.legacysqlarchitect.db.MetadataReader;
import io.github.tabforgeai.legacysqlarchitect.model.ColumnInfo;
import io.github.tabforgeai.legacysqlarchitect.model.ProcedureInfo;
import io.github.tabforgeai.legacysqlarchitect.model.TableInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP tool: generate_documentation
 *
 * Generates a complete Markdown documentation document for a database schema.
 * The output is a self-contained Markdown file that can be saved directly as
 * {@code DATABASE.md} (or any name) in the project repository. It is intended
 * to be the definitive human-readable reference for the database structure,
 * particularly useful when onboarding developers to a legacy system.
 *
 * <p>The generated document contains four sections:
 * <ol>
 *   <li><b>Header</b> — schema name, generation date, table and procedure counts</li>
 *   <li><b>Entity-Relationship Diagram</b> — an embedded Mermaid ERD (same output
 *       as {@code generate_mermaid_erd}) wrapped in a {@code ```mermaid} code fence,
 *       rendering natively in GitHub, GitLab, and Notion</li>
 *   <li><b>Table Reference</b> — one section per table with:
 *       <ul>
 *         <li>Table comment (if present in the database)</li>
 *         <li>A Markdown table listing each column: name, type, nullable, PK/FK flags,
 *             and the referenced table for FK columns</li>
 *         <li>Index list (if any non-PK indexes exist)</li>
 *       </ul>
 *   </li>
 *   <li><b>Stored Procedures &amp; Functions</b> — one subsection per routine with
 *       its type, language, and full source code in a SQL code fence</li>
 * </ol>
 *
 * <p>Tool input parameters:
 * <ul>
 *   <li>{@code schema}            (optional) Database schema name. Defaults to config.json db_schema.</li>
 *   <li>{@code table_filter}      (optional) SQL LIKE pattern to restrict which tables appear
 *                                 in the document (e.g. {@code "ORD%"}). Defaults to {@code "%"}.</li>
 *   <li>{@code include_procedures}(optional, boolean) If true (default), stored procedures and
 *                                 functions are included as a final section. Set to false for
 *                                 large schemas where procedure source would make the document
 *                                 too long.</li>
 * </ul>
 *
 * <p>Abbreviated example of the generated Markdown output:
 * <pre>
 * # Database Schema: public
 *
 * | Property | Value |
 * |----------|-------|
 * | Schema   | public |
 * | Generated | 2026-03-12 |
 * | Tables   | 3 |
 * | Procedures / Functions | 1 |
 *
 * ## Entity-Relationship Diagram
 *
 * ```mermaid
 * erDiagram
 *     CUSTOMERS { bigint id PK ... }
 *     ORDERS { bigint id PK ... }
 *     CUSTOMERS ||--o{ ORDERS : "customer_id"
 * ```
 *
 * ## Tables
 *
 * ### CUSTOMERS
 *
 * > Customer master records
 *
 * | Column | Type | Nullable | Key | References |
 * |--------|------|----------|-----|------------|
 * | id | bigint | No | PK | |
 * | name | varchar | No | | |
 * | email | varchar | Yes | | |
 *
 * ### ORDERS
 *
 * | Column | Type | Nullable | Key | References |
 * |--------|------|----------|-----|------------|
 * | id | bigint | No | PK | |
 * | customer_id | bigint | No | FK | customers.id |
 * | status | varchar | Yes | | |
 *
 * **Indexes:** idx_orders_customer, idx_orders_status
 *
 * ## Stored Procedures & Functions
 *
 * ### calculate_discount
 * - **Type:** FUNCTION
 * - **Language:** plpgsql
 *
 * ```sql
 * CREATE OR REPLACE FUNCTION calculate_discount(customer_tier text)
 *  RETURNS numeric ...
 * ```
 * </pre>
 *
 * <p>Downstream usage — this tool's output is consumed by:
 * <ul>
 *   <li>AI agent: saves the output as {@code DATABASE.md} in the project repository</li>
 *   <li>Human developers: read the document directly; the embedded ERD renders in GitHub/GitLab</li>
 *   <li>Migration projects: use as baseline documentation before schema refactoring</li>
 * </ul>
 */
public class GenerateDocumentation implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(GenerateDocumentation.class);

    /** Tool name as registered with the MCP server and visible to the AI agent. */
    public static final String TOOL_NAME = "generate_documentation";

    private final JdbcClient jdbcClient;

    /**
     * Creates a new GenerateDocumentation tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public GenerateDocumentation(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for generate_documentation.
     *
     * @param jsonMapper the MCP SDK's JSON mapper for parsing the input schema string
     * @return a fully configured McpSchema.Tool ready for registration
     */
    public static McpSchema.Tool toolDefinition(McpJsonMapper jsonMapper) {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "schema": {
                      "type": "string",
                      "description": "Database schema name. Defaults to the schema in config.json."
                    },
                    "table_filter": {
                      "type": "string",
                      "description": "SQL LIKE pattern to restrict which tables are documented (e.g. 'ORD%'). Defaults to '%' (all tables)."
                    },
                    "include_procedures": {
                      "type": "boolean",
                      "description": "If true (default), stored procedures and functions are included as a final section with their full source code."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Generates a complete Markdown documentation document for the database schema. \
                        Includes a summary header, an embedded Mermaid ERD diagram, a detailed \
                        table reference with column types and FK relationships, and (optionally) \
                        stored procedure source code. The output can be saved directly as DATABASE.md \
                        in the project repository.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the generate_documentation tool when called by the AI agent.
     *
     * Reads schema metadata and (optionally) procedure source, then assembles
     * the full Markdown document in four sections: header, ERD, table reference,
     * and procedures.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request with optional schema, table_filter, include_procedures
     * @return a CallToolResult containing the full Markdown document as plain text
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        String schema            = resolveSchema(args);
        String tableFilter       = args != null ? (String) args.get("table_filter") : null;
        boolean includeProcedures = resolveIncludeProcedures(args);

        log.info("Tool '{}' called: schema={}, table_filter={}, include_procedures={}",
                TOOL_NAME, schema, tableFilter, includeProcedures);

        try {
            MetadataReader reader = new MetadataReader(jdbcClient.getConnection());
            List<TableInfo> tables = reader.readTables(schema, tableFilter);

            if (tables.isEmpty()) {
                String msg = tableFilter != null
                        ? "No tables matching '" + tableFilter + "' found in schema '" + schema + "'."
                        : "No tables found in schema '" + schema + "'.";
                return McpSchema.CallToolResult.builder().addTextContent(msg).build();
            }

            List<ProcedureInfo> procedures = includeProcedures
                    ? fetchProcedures(schema)
                    : List.of();

            String markdown = buildMarkdown(schema, tables, procedures);

            log.info("generate_documentation produced {} chars for {} tables, {} procedures",
                    markdown.length(), tables.size(), procedures.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(markdown)
                    .build();

        } catch (Exception e) {
            log.error("generate_documentation failed: {}", e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error generating documentation: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Document assembly
    // -------------------------------------------------------------------------

    /**
     * Assembles the complete Markdown document from schema metadata.
     *
     * @param schema     the schema name (used in the document header)
     * @param tables     the list of tables to document
     * @param procedures the list of procedures to include (may be empty)
     * @return the full Markdown document as a string
     */
    private String buildMarkdown(String schema,
                                 List<TableInfo> tables,
                                 List<ProcedureInfo> procedures) {
        StringBuilder sb = new StringBuilder();

        appendHeader(sb, schema, tables, procedures);
        appendErd(sb, tables);
        appendTableReference(sb, tables);

        if (!procedures.isEmpty()) {
            appendProcedures(sb, procedures);
        }

        return sb.toString();
    }

    /**
     * Appends the document header: title and a summary properties table.
     *
     * @param sb         the StringBuilder to append to
     * @param schema     the schema name
     * @param tables     the tables (used for count)
     * @param procedures the procedures (used for count)
     */
    private void appendHeader(StringBuilder sb, String schema,
                              List<TableInfo> tables, List<ProcedureInfo> procedures) {
        sb.append("# Database Schema: ").append(schema).append("\n\n");

        sb.append("| Property | Value |\n");
        sb.append("|----------|-------|\n");
        sb.append("| Schema | `").append(schema).append("` |\n");
        sb.append("| Generated | ").append(LocalDate.now()).append(" |\n");
        sb.append("| Tables | ").append(tables.size()).append(" |\n");
        sb.append("| Stored Procedures / Functions | ").append(procedures.size()).append(" |\n");
        sb.append("\n");
    }

    /**
     * Appends the ERD section with an embedded Mermaid diagram.
     *
     * Reuses the same diagram-building logic as {@link GenerateMermaidErd}
     * so the ERD in the documentation is always identical to what
     * {@code generate_mermaid_erd} would produce for the same set of tables.
     *
     * @param sb     the StringBuilder to append to
     * @param tables the tables to include in the ERD
     */
    private void appendErd(StringBuilder sb, List<TableInfo> tables) {
        sb.append("## Entity-Relationship Diagram\n\n");
        sb.append("```mermaid\n");

        // Delegate ERD generation to the shared helper on GenerateMermaidErd.
        // We instantiate it as a helper to avoid duplicating the ERD-building code.
        ErdBuilder erd = new ErdBuilder();
        sb.append(erd.build(tables, true));

        sb.append("```\n\n");
    }

    /**
     * Appends the full table reference section.
     * Each table gets its own subsection with column table and index list.
     *
     * @param sb     the StringBuilder to append to
     * @param tables the tables to document
     */
    private void appendTableReference(StringBuilder sb, List<TableInfo> tables) {
        sb.append("## Tables\n\n");

        for (TableInfo table : tables) {
            sb.append("### ").append(table.getName()).append("\n\n");

            // Table comment (if present)
            if (table.getComment() != null && !table.getComment().isBlank()) {
                sb.append("> ").append(table.getComment()).append("\n\n");
            }

            // Column table
            sb.append("| Column | Type | Nullable | Key | References |\n");
            sb.append("|--------|------|----------|-----|------------|\n");

            for (ColumnInfo col : table.getColumns()) {
                String key  = buildKeyCell(col);
                String refs = buildRefsCell(col);
                sb.append("| ").append(col.getName())
                  .append(" | ").append(col.getType())
                  .append(" | ").append(col.isNullable() ? "Yes" : "No")
                  .append(" | ").append(key)
                  .append(" | ").append(refs)
                  .append(" |\n");
            }

            // Index list
            if (!table.getIndexes().isEmpty()) {
                sb.append("\n**Indexes:** ");
                sb.append(String.join(", ", table.getIndexes()));
                sb.append("\n");
            }

            sb.append("\n");
        }
    }

    /**
     * Builds the "Key" cell value for a column in the column table.
     * A column may be both a PK and an FK (e.g. in a junction/composite-PK table).
     *
     * @param col the column
     * @return "PK", "FK", "PK, FK", or empty string
     */
    private String buildKeyCell(ColumnInfo col) {
        boolean pk = col.isPrimaryKey();
        boolean fk = col.getForeignKeyTable() != null;
        if (pk && fk) return "PK, FK";
        if (pk)       return "PK";
        if (fk)       return "FK";
        return "";
    }

    /**
     * Builds the "References" cell value for FK columns.
     * Returns "table.column" for FK columns, or empty string for non-FK columns.
     *
     * @param col the column
     * @return "referenced_table.referenced_column" or empty string
     */
    private String buildRefsCell(ColumnInfo col) {
        if (col.getForeignKeyTable() == null) return "";
        return col.getForeignKeyTable() + "." + col.getForeignKeyColumn();
    }

    /**
     * Appends the stored procedures and functions section.
     * Each procedure gets a subsection with its metadata and full source code
     * in a SQL code fence.
     *
     * @param sb         the StringBuilder to append to
     * @param procedures the procedures to document
     */
    private void appendProcedures(StringBuilder sb, List<ProcedureInfo> procedures) {
        sb.append("## Stored Procedures & Functions\n\n");

        for (ProcedureInfo proc : procedures) {
            sb.append("### ").append(proc.getName()).append("\n\n");
            sb.append("- **Type:** ").append(proc.getType()).append("\n");
            sb.append("- **Language:** ").append(proc.getLanguage()).append("\n\n");

            String src = proc.getSourceCode();
            if (src != null && !src.isBlank()) {
                sb.append("```sql\n");
                sb.append(src.stripTrailing());
                sb.append("\n```\n\n");
            } else {
                sb.append("_Source not available (encrypted or inaccessible)._\n\n");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Procedure fetching
    // -------------------------------------------------------------------------

    /**
     * Fetches all stored procedures and functions from the database.
     * Delegates to the appropriate database-specific query based on db_type.
     *
     * @param schema the schema to query
     * @return list of ProcedureInfo objects with source code populated
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> fetchProcedures(String schema) throws SQLException {
        String dbType = jdbcClient.getConfig().getDbType();
        if ("sqlserver".equalsIgnoreCase(dbType)) {
            return fetchProceduresSqlServer(schema);
        } else {
            return fetchProceduresPostgres(schema);
        }
    }

    /**
     * Fetches procedures from PostgreSQL using pg_get_functiondef().
     * Same query as used by {@link GetProcedureSource} — kept here to avoid
     * a dependency between two tool classes.
     *
     * @param schema the PostgreSQL schema name
     * @return list of ProcedureInfo objects
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> fetchProceduresPostgres(String schema) throws SQLException {
        String sql = """
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
                ORDER  BY p.proname
                """;

        return executeProcedureQuery(sql, schema);
    }

    /**
     * Fetches procedures from SQL Server using sys.sql_modules.
     * Same query as used by {@link GetProcedureSource}.
     *
     * @param schema the SQL Server schema name (e.g. "dbo")
     * @return list of ProcedureInfo objects
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> fetchProceduresSqlServer(String schema) throws SQLException {
        String sql = """
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
                ORDER  BY o.name
                """;

        return executeProcedureQuery(sql, schema);
    }

    /**
     * Executes a procedure query (either Postgres or SQL Server variant) and
     * maps the ResultSet to a list of ProcedureInfo objects.
     *
     * @param sql    the parameterized query; expects one String parameter (schema)
     * @param schema the schema name to bind as the query parameter
     * @return list of ProcedureInfo objects
     * @throws SQLException if the query fails
     */
    private List<ProcedureInfo> executeProcedureQuery(String sql, String schema) throws SQLException {
        List<ProcedureInfo> result = new ArrayList<>();
        try (PreparedStatement ps = jdbcClient.getConnection().prepareStatement(sql)) {
            ps.setString(1, schema);
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

    // -------------------------------------------------------------------------
    // Config helpers
    // -------------------------------------------------------------------------

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

    /**
     * Resolves the include_procedures flag from request arguments.
     * Defaults to true if not specified.
     *
     * @param args the tool call arguments map (may be null)
     * @return true if procedures should be included in the document
     */
    private boolean resolveIncludeProcedures(Map<String, Object> args) {
        if (args != null && args.get("include_procedures") instanceof Boolean b) {
            return b;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Inner helper: ERD builder (reuses GenerateMermaidErd logic inline)
    // -------------------------------------------------------------------------

    /**
     * Lightweight inner class that reproduces the Mermaid ERD-building logic
     * from {@link GenerateMermaidErd} without requiring an instance of that
     * class (which needs a JdbcClient and would re-query the database).
     *
     * Having this as a private inner class keeps {@code GenerateDocumentation}
     * self-contained and avoids coupling the two tool classes together.
     */
    private static class ErdBuilder {

        private static final String INDENT = "    ";

        /**
         * Builds a Mermaid ERD string from pre-loaded table metadata.
         * Logic is intentionally kept in sync with {@link GenerateMermaidErd#buildMermaidErd}.
         *
         * @param tables      the tables to include
         * @param includeCols whether to include column definitions in entity blocks
         * @return Mermaid ERD text (without the ```mermaid fence)
         */
        String build(List<TableInfo> tables, boolean includeCols) {
            StringBuilder sb = new StringBuilder("erDiagram\n");

            // --- Entity blocks ---
            for (TableInfo table : tables) {
                sb.append(INDENT).append(sanitize(table.getName()));
                if (includeCols && !table.getColumns().isEmpty()) {
                    sb.append(" {\n");
                    for (ColumnInfo col : table.getColumns()) {
                        sb.append(INDENT).append(INDENT)
                          .append(normalizeType(col.getType())).append(" ")
                          .append(col.getName());
                        boolean pk = col.isPrimaryKey();
                        boolean fk = col.getForeignKeyTable() != null;
                        if (pk && fk)  sb.append(" PK,FK");
                        else if (pk)   sb.append(" PK");
                        else if (fk)   sb.append(" FK");
                        sb.append("\n");
                    }
                    sb.append(INDENT).append("}\n");
                } else {
                    sb.append(" {\n").append(INDENT).append("}\n");
                }
            }

            // --- Relationship lines ---
            boolean first = true;
            for (TableInfo table : tables) {
                for (ColumnInfo col : table.getColumns()) {
                    if (col.getForeignKeyTable() != null && tableInList(tables, col.getForeignKeyTable())) {
                        if (first) { sb.append("\n"); first = false; }
                        sb.append(INDENT)
                          .append(sanitize(col.getForeignKeyTable()))
                          .append(" ||--o{ ")
                          .append(sanitize(table.getName()))
                          .append(" : \"").append(col.getName()).append("\"\n");
                    }
                }
            }

            return sb.toString();
        }

        private boolean tableInList(List<TableInfo> tables, String name) {
            for (TableInfo t : tables) {
                if (t.getName().equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        private String sanitize(String name) {
            if (name == null) return "UNKNOWN";
            return name.matches("[\\w]+") ? name.toUpperCase() : "\"" + name + "\"";
        }

        private String normalizeType(String type) {
            if (type == null) return "unknown";
            return switch (type.toLowerCase()) {
                case "character varying", "character varying(n)" -> "varchar";
                case "character"                                  -> "char";
                case "integer"                                    -> "int";
                case "double precision"                           -> "float";
                case "timestamp without time zone"                -> "timestamp";
                case "timestamp with time zone"                   -> "timestamptz";
                case "time without time zone"                     -> "time";
                case "time with time zone"                        -> "timetz";
                default -> type.replace(" ", "_");
            };
        }
    }
}
