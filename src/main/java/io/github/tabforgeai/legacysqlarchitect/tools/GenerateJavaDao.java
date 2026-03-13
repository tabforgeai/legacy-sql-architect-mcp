package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.github.tabforgeai.legacysqlarchitect.db.MetadataReader;
import io.github.tabforgeai.legacysqlarchitect.model.ColumnInfo;
import io.github.tabforgeai.legacysqlarchitect.model.TableInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

/**
 * MCP tool: generate_java_dao
 *
 * Generates Java source code skeletons for database access: one Entity class
 * (a plain POJO) and one Repository class (plain JDBC, no frameworks) per table.
 * The output is ready-to-compile Java 17+ code that provides a starting point for
 * building the data access layer on top of a legacy database.
 *
 * <p>This tool is intentionally framework-agnostic: it uses only plain JDBC and
 * {@code java.sql.*} — no Spring, no JPA, no Hibernate. This makes the generated
 * code suitable for any Java project regardless of the framework stack.
 *
 * <p>For each table the tool generates two classes:
 * <ol>
 *   <li><b>Entity</b> — a POJO with:
 *     <ul>
 *       <li>One field per column with the appropriate Java type (see type mapping below)</li>
 *       <li>A no-arg constructor</li>
 *       <li>Getters and setters for all fields</li>
 *       <li>A {@code toString()} method listing all fields</li>
 *     </ul>
 *   </li>
 *   <li><b>Repository</b> — a plain JDBC repository with:
 *     <ul>
 *       <li>{@code findById(PkType id)} — single-row lookup by primary key</li>
 *       <li>{@code findAll()} — full table scan (no pagination — add as needed)</li>
 *       <li>{@code insert(Entity e)} — INSERT of all non-PK columns</li>
 *       <li>{@code update(Entity e)} — UPDATE of all non-PK columns WHERE pk = ?</li>
 *       <li>{@code deleteById(PkType id)} — DELETE WHERE pk = ?</li>
 *       <li>Private {@code mapRow(ResultSet rs)} — maps a result row to the entity</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>SQL-to-Java type mapping used by this tool:
 * <pre>
 * bigint, int8               → Long
 * integer, int, int4         → Integer
 * smallint, tinyint          → Integer
 * boolean, bool              → Boolean
 * numeric, decimal           → java.math.BigDecimal
 * real, float4               → Float
 * double precision, float8   → Double
 * varchar, character varying → String
 * char, character, text      → String
 * json, jsonb                → String  (raw JSON text)
 * date                       → java.time.LocalDate
 * timestamp (no tz)          → java.time.LocalDateTime
 * timestamp (with tz)        → java.time.OffsetDateTime
 * time                       → java.time.LocalTime
 * uuid                       → java.util.UUID
 * bytea, binary, blob        → byte[]
 * (anything else)            → Object  (cast manually)
 * </pre>
 *
 * <p>Tool input parameters:
 * <ul>
 *   <li>{@code schema}       (optional) Database schema. Defaults to config.json db_schema.</li>
 *   <li>{@code table_filter} (optional) SQL LIKE pattern to select tables (e.g. {@code "ORD%"}).
 *                            Defaults to {@code "%"} (all tables).</li>
 *   <li>{@code package_name} (optional) Java package for the generated classes.
 *                            Defaults to {@code "com.example.dao"}.</li>
 * </ul>
 *
 * <p>Tool output — JSON array, one entry per table:
 * <pre>
 * [
 *   {
 *     "table": "orders",
 *     "entity_class_name": "Orders",
 *     "repository_class_name": "OrdersRepository",
 *     "entity_source": "package com.example.dao;\n\nimport java.math.BigDecimal;\n...\npublic class Orders {\n    private Long id;\n    ...\n}",
 *     "repository_source": "package com.example.dao;\n\nimport java.sql.*;\n...\npublic class OrdersRepository {\n    ...\n}"
 *   }
 * ]
 * </pre>
 *
 * <p>Naming convention: table names are converted from {@code snake_case} to {@code PascalCase}
 * (e.g. {@code order_items} → {@code OrderItems}). If you prefer singular class names
 * (e.g. {@code OrderItem}), rename after generation — the AI agent can do this automatically
 * when asked.
 *
 * <p>Downstream usage — this tool's output is consumed by:
 * <ul>
 *   <li>AI agent: writes the generated source files into the project's src/main/java tree</li>
 *   <li>Human developer: uses the generated skeletons as a starting point, adds business
 *       logic, pagination, and custom queries on top</li>
 *   <li>Migration projects: quickly bootstraps a Java data access layer for a legacy DB
 *       that previously had no Java code at all</li>
 * </ul>
 */
public class GenerateJavaDao implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(GenerateJavaDao.class);

    /** Tool name as registered with the MCP server and visible to the AI agent. */
    public static final String TOOL_NAME = "generate_java_dao";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Default Java package for generated classes when none is specified by the caller. */
    private static final String DEFAULT_PACKAGE = "com.example.dao";

    private final JdbcClient jdbcClient;

    /**
     * Creates a new GenerateJavaDao tool instance.
     *
     * @param jdbcClient the shared database client; must be already connected
     */
    public GenerateJavaDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns the MCP tool definition for generate_java_dao.
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
                      "description": "SQL LIKE pattern to select which tables to generate code for (e.g. 'ORD%'). Defaults to '%' (all tables)."
                    },
                    "package_name": {
                      "type": "string",
                      "description": "Java package name for the generated classes (e.g. 'com.acme.repository'). Defaults to 'com.example.dao'."
                    }
                  }
                }
                """;

        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("""
                        Generates plain Java JDBC code skeletons for database access: one Entity POJO \
                        and one Repository class per table. The repository includes findById, findAll, \
                        insert, update, and deleteById methods. No frameworks required — pure java.sql.*. \
                        Use table_filter to generate code for a subset of tables.""")
                .inputSchema(jsonMapper, inputSchema)
                .build();
    }

    /**
     * Executes the generate_java_dao tool when called by the AI agent.
     *
     * Reads schema metadata, then generates an Entity and Repository class
     * for each table matching the filter. Returns a JSON array with one
     * entry per table containing the generated source code strings.
     *
     * @param exchange the MCP server exchange context (not used by this tool)
     * @param request  the tool call request with optional schema, table_filter, package_name
     * @return a CallToolResult containing the generated Java source as JSON
     */
    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();

        String schema      = resolveSchema(args);
        String tableFilter = args != null ? (String) args.get("table_filter") : null;
        String packageName = resolvePackageName(args);

        log.info("Tool '{}' called: schema={}, table_filter={}, package={}",
                TOOL_NAME, schema, tableFilter, packageName);

        try {
            MetadataReader reader = new MetadataReader(jdbcClient.getConnection());
            List<TableInfo> tables = reader.readTables(schema, tableFilter);

            if (tables.isEmpty()) {
                String msg = tableFilter != null
                        ? "No tables matching '" + tableFilter + "' found in schema '" + schema + "'."
                        : "No tables found in schema '" + schema + "'.";
                return McpSchema.CallToolResult.builder().addTextContent(msg).build();
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (TableInfo table : tables) {
                String entityName = toPascalCase(table.getName());
                String repoName   = entityName + "Repository";

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("table",                 table.getName());
                entry.put("entity_class_name",     entityName);
                entry.put("repository_class_name", repoName);
                entry.put("entity_source",         generateEntity(table, packageName, entityName));
                entry.put("repository_source",     generateRepository(table, packageName, entityName, schema));
                results.add(entry);
            }

            log.info("generate_java_dao generated code for {} tables", results.size());

            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(results))
                    .build();

        } catch (Exception e) {
            log.error("generate_java_dao failed: {}", e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error generating Java DAO: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Entity generation
    // -------------------------------------------------------------------------

    /**
     * Generates the complete Java source code for an Entity POJO.
     *
     * The entity contains one field per column with the appropriate Java type,
     * a no-arg constructor, getters and setters, and a toString() implementation.
     * Import statements are derived automatically from the field types used.
     *
     * @param table       the table metadata
     * @param packageName the Java package for the class
     * @param className   the PascalCase class name
     * @return the complete Java source as a string
     */
    private String generateEntity(TableInfo table, String packageName, String className) {
        StringBuilder sb = new StringBuilder();

        // Determine which imports are needed based on column types
        Set<String> imports = new LinkedHashSet<>();
        for (ColumnInfo col : table.getColumns()) {
            String imp = importForType(col.getType());
            if (imp != null) imports.add(imp);
        }

        // Package declaration
        sb.append("package ").append(packageName).append(";\n\n");

        // Imports
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        if (!imports.isEmpty()) sb.append("\n");

        // Class JavaDoc
        sb.append("/**\n");
        sb.append(" * Entity class for table: ")
          .append(table.getSchema()).append(".").append(table.getName()).append("\n");
        if (table.getComment() != null && !table.getComment().isBlank()) {
            sb.append(" * ").append(table.getComment()).append("\n");
        }
        sb.append(" *\n");
        sb.append(" * Generated by Legacy SQL Architect MCP.\n");
        sb.append(" * Rename to singular form (e.g. Order, Customer) if preferred.\n");
        sb.append(" */\n");

        sb.append("public class ").append(className).append(" {\n\n");

        // Fields
        for (ColumnInfo col : table.getColumns()) {
            String javaType  = javaType(col.getType());
            String fieldName = toCamelCase(col.getName());
            String comment   = buildFieldComment(col);
            if (comment != null) {
                sb.append("    /** ").append(comment).append(" */\n");
            }
            sb.append("    private ").append(javaType).append(" ").append(fieldName).append(";\n");
        }
        sb.append("\n");

        // No-arg constructor
        sb.append("    public ").append(className).append("() {}\n\n");

        // Getters and setters
        for (ColumnInfo col : table.getColumns()) {
            String javaType   = javaType(col.getType());
            String fieldName  = toCamelCase(col.getName());
            String upperField = capitalize(fieldName);

            sb.append("    public ").append(javaType).append(" get").append(upperField)
              .append("() { return ").append(fieldName).append("; }\n");
            sb.append("    public void set").append(upperField)
              .append("(").append(javaType).append(" ").append(fieldName)
              .append(") { this.").append(fieldName).append(" = ").append(fieldName).append("; }\n\n");
        }

        // toString
        sb.append("    @Override\n");
        sb.append("    public String toString() {\n");
        sb.append("        return \"").append(className).append("{\" +\n");
        List<ColumnInfo> cols = table.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            String fieldName = toCamelCase(cols.get(i).getName());
            String separator = (i == 0) ? "" : ", ";
            sb.append("            \"").append(separator).append(fieldName)
              .append("=\" + ").append(fieldName);
            if (i < cols.size() - 1) sb.append(" +\n");
        }
        sb.append(" + \"}\";\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Repository generation
    // -------------------------------------------------------------------------

    /**
     * Generates the complete Java source code for a plain JDBC Repository class.
     *
     * The repository provides five standard operations: findById, findAll, insert,
     * update, and deleteById. A private mapRow() method handles ResultSet-to-entity
     * mapping. All methods declare {@code throws SQLException} — error handling
     * strategy is left to the application layer.
     *
     * If the table has no single-column primary key (e.g. composite PKs), findById,
     * update, and deleteById are generated with a placeholder comment explaining
     * the limitation, and the body throws {@code UnsupportedOperationException}.
     *
     * @param table       the table metadata
     * @param packageName the Java package for the class
     * @param entityName  the PascalCase entity class name
     * @param schema      the database schema name (used in SQL strings)
     * @return the complete Java source as a string
     */
    private String generateRepository(TableInfo table, String packageName,
                                       String entityName, String schema) {
        StringBuilder sb = new StringBuilder();

        String repoName      = entityName + "Repository";
        String qualifiedTable = "\\\"" + schema + "\\\".\\\"" + table.getName() + "\\\"";

        // Find the primary key column (single-column PK only)
        ColumnInfo pkColumn = table.getColumns().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .findFirst()
                .orElse(null);
        boolean hasSinglePk = pkColumn != null;

        // Non-PK columns for INSERT and UPDATE
        List<ColumnInfo> nonPkCols = table.getColumns().stream()
                .filter(c -> !c.isPrimaryKey())
                .toList();

        // Package and imports
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import java.sql.*;\n");
        sb.append("import java.util.*;\n\n");

        // Class JavaDoc
        sb.append("/**\n");
        sb.append(" * Plain JDBC repository for table: ")
          .append(schema).append(".").append(table.getName()).append("\n");
        sb.append(" * Provides basic CRUD operations.\n");
        sb.append(" *\n");
        sb.append(" * Generated by Legacy SQL Architect MCP.\n");
        sb.append(" * Add custom query methods as needed.\n");
        sb.append(" */\n");

        sb.append("public class ").append(repoName).append(" {\n\n");
        sb.append("    private final Connection connection;\n\n");
        sb.append("    public ").append(repoName).append("(Connection connection) {\n");
        sb.append("        this.connection = connection;\n");
        sb.append("    }\n\n");

        // findById
        appendFindById(sb, table, entityName, qualifiedTable, pkColumn, hasSinglePk);

        // findAll
        appendFindAll(sb, table, entityName, qualifiedTable);

        // insert
        appendInsert(sb, table, entityName, qualifiedTable, nonPkCols);

        // update
        appendUpdate(sb, table, entityName, qualifiedTable, pkColumn, hasSinglePk, nonPkCols);

        // deleteById
        appendDeleteById(sb, pkColumn, hasSinglePk, qualifiedTable);

        // mapRow
        appendMapRow(sb, table, entityName);

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Appends the {@code findById} method to the repository source.
     *
     * Uses {@code Optional} as the return type to handle the case where no
     * row with the given ID exists without throwing an exception.
     *
     * @param sb            the StringBuilder to append to
     * @param table         the table metadata
     * @param entityName    the entity class name
     * @param qualTable     the fully-qualified quoted table name string
     * @param pkCol         the primary key column (may be null)
     * @param hasSinglePk   true if a single-column PK was found
     */
    private void appendFindById(StringBuilder sb, TableInfo table, String entityName,
                                 String qualTable, ColumnInfo pkCol, boolean hasSinglePk) {
        if (!hasSinglePk) {
            sb.append("    // findById: table '").append(table.getName())
              .append("' has no single-column primary key — implement manually.\n\n");
            return;
        }

        String pkType  = javaType(pkCol.getType());
        String pkField = toCamelCase(pkCol.getName());

        sb.append("    public Optional<").append(entityName).append("> findById(")
          .append(pkType).append(" ").append(pkField).append(") throws SQLException {\n");
        sb.append("        String sql = \"SELECT * FROM ").append(qualTable)
          .append(" WHERE \\\"").append(pkCol.getName()).append("\\\" = ?\";\n");
        sb.append("        try (PreparedStatement ps = connection.prepareStatement(sql)) {\n");
        sb.append("            ps.setObject(1, ").append(pkField).append(");\n");
        sb.append("            try (ResultSet rs = ps.executeQuery()) {\n");
        sb.append("                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    /**
     * Appends the {@code findAll} method to the repository source.
     * Returns all rows as a List — add WHERE clause variants as needed.
     */
    private void appendFindAll(StringBuilder sb, TableInfo table, String entityName, String qualTable) {
        sb.append("    public List<").append(entityName).append("> findAll() throws SQLException {\n");
        sb.append("        String sql = \"SELECT * FROM ").append(qualTable).append("\";\n");
        sb.append("        List<").append(entityName).append("> result = new ArrayList<>();\n");
        sb.append("        try (Statement stmt = connection.createStatement();\n");
        sb.append("             ResultSet rs = stmt.executeQuery(sql)) {\n");
        sb.append("            while (rs.next()) result.add(mapRow(rs));\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    /**
     * Appends the {@code insert} method to the repository source.
     *
     * All non-PK columns are included in the INSERT. PK is excluded because
     * it is typically auto-generated by a sequence or identity column.
     * If all columns are PKs (unusual), all columns are inserted.
     */
    private void appendInsert(StringBuilder sb, TableInfo table, String entityName,
                               String qualTable, List<ColumnInfo> nonPkCols) {
        // If every column is a PK (rare composite-PK-only tables), insert all columns
        List<ColumnInfo> insertCols = nonPkCols.isEmpty() ? table.getColumns() : nonPkCols;

        String colList = buildQuotedColumnList(insertCols);
        String placeholders = "?".repeat(insertCols.size())
                .chars()
                .mapToObj(c -> "?")
                .collect(java.util.stream.Collectors.joining(", "));

        sb.append("    public int insert(").append(entityName).append(" entity) throws SQLException {\n");
        sb.append("        String sql = \"INSERT INTO ").append(qualTable)
          .append(" (").append(colList).append(") VALUES (").append(placeholders).append(")\";\n");
        sb.append("        try (PreparedStatement ps = connection.prepareStatement(sql)) {\n");

        for (int i = 0; i < insertCols.size(); i++) {
            ColumnInfo col = insertCols.get(i);
            sb.append("            ps.setObject(").append(i + 1).append(", entity.get")
              .append(capitalize(toCamelCase(col.getName()))).append("());\n");
        }

        sb.append("            return ps.executeUpdate();\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    /**
     * Appends the {@code update} method to the repository source.
     *
     * Sets all non-PK columns and uses the PK as the WHERE condition.
     * If no single-column PK is found, a comment placeholder is inserted instead.
     */
    private void appendUpdate(StringBuilder sb, TableInfo table, String entityName,
                               String qualTable, ColumnInfo pkCol, boolean hasSinglePk,
                               List<ColumnInfo> nonPkCols) {
        if (!hasSinglePk) {
            sb.append("    // update: table '").append(table.getName())
              .append("' has no single-column primary key — implement manually.\n\n");
            return;
        }

        if (nonPkCols.isEmpty()) {
            sb.append("    // update: table '").append(table.getName())
              .append("' has only PK columns — no columns to update.\n\n");
            return;
        }

        String setClause = nonPkCols.stream()
                .map(c -> "\\\"" + c.getName() + "\\\" = ?")
                .collect(java.util.stream.Collectors.joining(", "));

        sb.append("    public int update(").append(entityName).append(" entity) throws SQLException {\n");
        sb.append("        String sql = \"UPDATE ").append(qualTable)
          .append(" SET ").append(setClause)
          .append(" WHERE \\\"").append(pkCol.getName()).append("\\\" = ?\";\n");
        sb.append("        try (PreparedStatement ps = connection.prepareStatement(sql)) {\n");

        for (int i = 0; i < nonPkCols.size(); i++) {
            ColumnInfo col = nonPkCols.get(i);
            sb.append("            ps.setObject(").append(i + 1).append(", entity.get")
              .append(capitalize(toCamelCase(col.getName()))).append("());\n");
        }
        // PK is the last parameter (after all SET params)
        sb.append("            ps.setObject(").append(nonPkCols.size() + 1)
          .append(", entity.get").append(capitalize(toCamelCase(pkCol.getName()))).append("());\n");

        sb.append("            return ps.executeUpdate();\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    /**
     * Appends the {@code deleteById} method to the repository source.
     *
     * @param sb          the StringBuilder to append to
     * @param pkCol       the primary key column (may be null)
     * @param hasSinglePk true if a single-column PK was found
     * @param qualTable   the fully-qualified quoted table name string
     */
    private void appendDeleteById(StringBuilder sb, ColumnInfo pkCol, boolean hasSinglePk,
                                   String qualTable) {
        if (!hasSinglePk) {
            sb.append("    // deleteById: no single-column primary key — implement manually.\n\n");
            return;
        }

        String pkType  = javaType(pkCol.getType());
        String pkField = toCamelCase(pkCol.getName());

        sb.append("    public int deleteById(").append(pkType).append(" ").append(pkField)
          .append(") throws SQLException {\n");
        sb.append("        String sql = \"DELETE FROM ").append(qualTable)
          .append(" WHERE \\\"").append(pkCol.getName()).append("\\\" = ?\";\n");
        sb.append("        try (PreparedStatement ps = connection.prepareStatement(sql)) {\n");
        sb.append("            ps.setObject(1, ").append(pkField).append(");\n");
        sb.append("            return ps.executeUpdate();\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    /**
     * Appends the private {@code mapRow} method that converts a ResultSet row
     * to an entity instance.
     *
     * Uses {@code rs.getObject(columnName, Class)} where a specific Java type
     * is needed (e.g. LocalDate, LocalDateTime, UUID), and the appropriate typed
     * getter (getString, getLong, etc.) for common primitives.
     *
     * @param sb         the StringBuilder to append to
     * @param table      the table metadata
     * @param entityName the entity class name
     */
    private void appendMapRow(StringBuilder sb, TableInfo table, String entityName) {
        sb.append("    private ").append(entityName).append(" mapRow(ResultSet rs) throws SQLException {\n");
        sb.append("        ").append(entityName).append(" entity = new ").append(entityName).append("();\n");

        for (ColumnInfo col : table.getColumns()) {
            String fieldName    = toCamelCase(col.getName());
            String setterName   = "set" + capitalize(fieldName);
            String rsGetter     = rsGetter(col.getType(), col.getName());
            sb.append("        entity.").append(setterName).append("(").append(rsGetter).append(");\n");
        }

        sb.append("        return entity;\n");
        sb.append("    }\n");
    }

    // -------------------------------------------------------------------------
    // Type mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a SQL type name to the corresponding Java type name string.
     *
     * All mapped types are reference types (wrappers) rather than primitives
     * so that NULL database values can be represented as Java {@code null}.
     *
     * @param sqlType the SQL type name from DatabaseMetaData (e.g. "bigint", "varchar")
     * @return the Java type name string (e.g. "Long", "String", "LocalDateTime")
     */
    private String javaType(String sqlType) {
        if (sqlType == null) return "Object";
        return switch (sqlType.toLowerCase()) {
            case "bigint", "int8", "bigserial"           -> "Long";
            case "integer", "int", "int4", "serial"      -> "Integer";
            case "smallint", "int2", "tinyint", "smallserial" -> "Integer";
            case "boolean", "bool"                        -> "Boolean";
            case "numeric", "decimal"                     -> "java.math.BigDecimal";
            case "real", "float4"                         -> "Float";
            case "double precision", "float8"             -> "Double";
            case "date"                                   -> "java.time.LocalDate";
            case "timestamp", "timestamp without time zone" -> "java.time.LocalDateTime";
            case "timestamp with time zone", "timestamptz" -> "java.time.OffsetDateTime";
            case "time", "time without time zone"         -> "java.time.LocalTime";
            case "uuid"                                   -> "java.util.UUID";
            case "bytea", "binary", "varbinary", "blob"  -> "byte[]";
            default                                       -> "String"; // varchar, text, char, json, etc.
        };
    }

    /**
     * Returns the import statement needed for a given SQL type, or null
     * if the type maps to a java.lang type (which needs no import) or
     * a fully-qualified inline type.
     *
     * Types that include their package inline (e.g. "java.math.BigDecimal")
     * are added as imports here so the class header stays clean.
     *
     * @param sqlType the SQL type name
     * @return an import string (e.g. "java.time.LocalDate") or null
     */
    private String importForType(String sqlType) {
        if (sqlType == null) return null;
        return switch (sqlType.toLowerCase()) {
            case "numeric", "decimal"                      -> "java.math.BigDecimal";
            case "date"                                    -> "java.time.LocalDate";
            case "timestamp", "timestamp without time zone" -> "java.time.LocalDateTime";
            case "timestamp with time zone", "timestamptz" -> "java.time.OffsetDateTime";
            case "time", "time without time zone"          -> "java.time.LocalTime";
            case "uuid"                                    -> "java.util.UUID";
            default                                        -> null;
        };
    }

    /**
     * Builds the ResultSet getter expression for a given SQL type and column name.
     *
     * For types that have a direct typed getter in JDBC (getString, getLong, etc.),
     * the appropriate getter is used for clarity. For Java time types and UUID,
     * {@code rs.getObject(columnName, Class)} is used because JDBC 4.2+ supports
     * direct mapping to java.time types, and this avoids deprecated conversion methods.
     *
     * @param sqlType    the SQL type name
     * @param columnName the database column name (used as the ResultSet column label)
     * @return a Java expression string that retrieves the column value from {@code rs}
     */
    private String rsGetter(String sqlType, String columnName) {
        String col = "\"" + columnName + "\"";
        if (sqlType == null) return "rs.getObject(" + col + ")";
        return switch (sqlType.toLowerCase()) {
            case "bigint", "int8", "bigserial"            -> "rs.getLong(" + col + ")";
            case "integer", "int", "int4", "serial",
                 "smallint", "int2", "tinyint", "smallserial" -> "rs.getInt(" + col + ")";
            case "boolean", "bool"                         -> "rs.getBoolean(" + col + ")";
            case "numeric", "decimal"                      -> "rs.getBigDecimal(" + col + ")";
            case "real", "float4"                          -> "rs.getFloat(" + col + ")";
            case "double precision", "float8"              -> "rs.getDouble(" + col + ")";
            case "date"                       -> "rs.getObject(" + col + ", java.time.LocalDate.class)";
            case "timestamp", "timestamp without time zone"
                                              -> "rs.getObject(" + col + ", java.time.LocalDateTime.class)";
            case "timestamp with time zone", "timestamptz"
                                              -> "rs.getObject(" + col + ", java.time.OffsetDateTime.class)";
            case "time", "time without time zone"
                                              -> "rs.getObject(" + col + ", java.time.LocalTime.class)";
            case "uuid"                       -> "java.util.UUID.fromString(rs.getString(" + col + "))";
            case "bytea", "binary", "varbinary", "blob"   -> "rs.getBytes(" + col + ")";
            default                                        -> "rs.getString(" + col + ")";
        };
    }

    // -------------------------------------------------------------------------
    // Name conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a {@code snake_case} database identifier to {@code PascalCase}.
     *
     * Examples:
     * <ul>
     *   <li>{@code orders} → {@code Orders}</li>
     *   <li>{@code order_items} → {@code OrderItems}</li>
     *   <li>{@code CUST_ADDR} → {@code CustAddr}</li>
     * </ul>
     *
     * @param name the snake_case database identifier
     * @return the PascalCase Java class name
     */
    private String toPascalCase(String name) {
        if (name == null || name.isBlank()) return "Unknown";
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toLowerCase().toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Converts a {@code snake_case} database identifier to {@code camelCase}.
     *
     * Examples:
     * <ul>
     *   <li>{@code customer_id} → {@code customerId}</li>
     *   <li>{@code created_at} → {@code createdAt}</li>
     *   <li>{@code ID} → {@code id}</li>
     * </ul>
     *
     * @param name the snake_case database column name
     * @return the camelCase Java field name
     */
    private String toCamelCase(String name) {
        String pascal = toPascalCase(name);
        if (pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * Capitalizes the first character of a string.
     * Used to build getter/setter method names from field names.
     *
     * @param s the input string
     * @return the string with its first character uppercased
     */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Builds a comma-separated list of double-quoted column names for SQL statements.
     * Example: {@code "customer_id", "status", "total"}
     *
     * @param columns the list of columns to include
     * @return the column list string
     */
    private String buildQuotedColumnList(List<ColumnInfo> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\\\"").append(columns.get(i).getName()).append("\\\"");
        }
        return sb.toString();
    }

    /**
     * Builds a brief Javadoc comment for a column field based on its metadata.
     * Returns null if there is nothing useful to say (no comment, not PK, not FK).
     *
     * @param col the column to describe
     * @return a one-line comment string, or null if nothing to document
     */
    private String buildFieldComment(ColumnInfo col) {
        if (col.getComment() != null && !col.getComment().isBlank()) {
            return col.getComment();
        }
        if (col.isPrimaryKey() && col.getForeignKeyTable() != null) {
            return "Primary key. FK → " + col.getForeignKeyTable() + "." + col.getForeignKeyColumn();
        }
        if (col.isPrimaryKey()) return "Primary key.";
        if (col.getForeignKeyTable() != null) {
            return "FK → " + col.getForeignKeyTable() + "." + col.getForeignKeyColumn();
        }
        return null;
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
     * Resolves the Java package name from request arguments or the built-in default.
     *
     * @param args the tool call arguments map (may be null)
     * @return the package name to use in generated source files
     */
    private String resolvePackageName(Map<String, Object> args) {
        if (args != null && args.get("package_name") instanceof String s && !s.isBlank()) {
            return s;
        }
        return DEFAULT_PACKAGE;
    }
}
