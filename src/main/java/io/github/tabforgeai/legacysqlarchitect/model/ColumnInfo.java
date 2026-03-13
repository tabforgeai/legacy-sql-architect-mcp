package io.github.tabforgeai.legacysqlarchitect.model;

/**
 * Represents a single column within a database table.
 *
 * Instances of this class are populated by {@code MetadataReader} using JDBC
 * {@code DatabaseMetaData.getColumns()}, {@code getPrimaryKeys()}, and
 * {@code getImportedKeys()}, then assembled into a {@link TableInfo} object.
 * The complete table structure (with all its ColumnInfo objects) is serialized
 * to JSON and returned to the AI agent as part of the {@code inspect_schema}
 * tool response.
 *
 * <p>Example JSON representation of two ColumnInfo objects inside a table:
 * <pre>
 * [
 *   {
 *     "name": "id",
 *     "type": "bigint",
 *     "size": 19,
 *     "primaryKey": true,
 *     "foreignKeyTable": null,
 *     "foreignKeyColumn": null,
 *     "nullable": false,
 *     "comment": "Surrogate primary key"
 *   },
 *   {
 *     "name": "customer_id",
 *     "type": "bigint",
 *     "size": 19,
 *     "primaryKey": false,
 *     "foreignKeyTable": "customers",
 *     "foreignKeyColumn": "id",
 *     "nullable": false,
 *     "comment": null
 *   }
 * ]
 * </pre>
 *
 * <p>Downstream consumers of this object:
 * <ul>
 *   <li>{@code inspect_schema} tool – serializes all columns as part of each table entry</li>
 *   <li>{@code generate_mermaid_erd} tool – reads {@code foreignKeyTable} and
 *       {@code foreignKeyColumn} to build Mermaid ERD relationship lines</li>
 *   <li>{@code generate_documentation} tool – uses {@code name}, {@code type}, {@code nullable},
 *       and {@code comment} to generate human-readable column documentation</li>
 *   <li>{@code generate_java_dao} tool – uses {@code type} and {@code primaryKey} to choose
 *       the correct Java field type and to annotate the generated Entity class</li>
 *   <li>{@code dependency_graph} / {@code find_impact} tools – inspect FK fields to trace
 *       inter-table dependencies</li>
 * </ul>
 */
public class ColumnInfo {

    /** Column name as reported by the database. */
    private String name;

    /**
     * SQL data type name (e.g., "varchar", "bigint", "timestamp").
     * Value comes from DatabaseMetaData.getColumns() TYPE_NAME field.
     */
    private String type;

    /** Maximum length for character/binary columns. 0 for non-applicable types. */
    private int size;

    /** True if this column is part of the primary key. */
    private boolean primaryKey;

    /**
     * Name of the referenced table if this column is a foreign key.
     * Null if this column is not a foreign key.
     * Format: "schema.table" or just "table" depending on the database.
     */
    private String foreignKeyTable;

    /**
     * Name of the referenced column if this column is a foreign key.
     * Null if this column is not a foreign key.
     */
    private String foreignKeyColumn;

    /** True if the column allows NULL values. */
    private boolean nullable;

    /**
     * Column comment/remark as defined in the database.
     * Many legacy databases leave this empty, but when present it is valuable
     * context for the AI agent to understand the column's purpose.
     */
    private String comment;

    // --- Constructors ---

    public ColumnInfo() {
    }

    // --- Getters and Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public String getForeignKeyTable() {
        return foreignKeyTable;
    }

    public void setForeignKeyTable(String foreignKeyTable) {
        this.foreignKeyTable = foreignKeyTable;
    }

    public String getForeignKeyColumn() {
        return foreignKeyColumn;
    }

    public void setForeignKeyColumn(String foreignKeyColumn) {
        this.foreignKeyColumn = foreignKeyColumn;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * Returns a compact human-readable representation of this column,
     * useful for logging and debugging.
     * Example: "customer_id bigint FK->customers.id"
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(type);
        if (primaryKey) sb.append(" PK");
        if (foreignKeyTable != null) sb.append(" FK->").append(foreignKeyTable).append(".").append(foreignKeyColumn);
        if (!nullable) sb.append(" NOT NULL");
        return sb.toString();
    }
}
