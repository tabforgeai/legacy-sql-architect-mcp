package io.github.tabforgeai.legacysqlarchitect.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single database table along with its full metadata.
 *
 * This is the primary data structure produced by the {@code inspect_schema} tool
 * (via {@code MetadataReader.readTables()}). Each TableInfo instance corresponds
 * to exactly one table in the target schema and is serialized to JSON as part
 * of the inspect_schema response.
 *
 * Foreign key information is stored on individual {@link ColumnInfo} objects
 * rather than at the table level, which makes it easier for the AI agent
 * to reason about individual column relationships.
 *
 * <p>Example JSON representation of a single TableInfo in the inspect_schema output:
 * <pre>
 * {
 *   "name": "orders",
 *   "schema": "public",
 *   "comment": "Customer order header records",
 *   "columns": [
 *     { "name": "id",          "type": "bigint",  "primaryKey": true,  "nullable": false },
 *     { "name": "customer_id", "type": "bigint",  "primaryKey": false, "nullable": false,
 *       "foreignKeyTable": "customers", "foreignKeyColumn": "id" },
 *     { "name": "status",      "type": "varchar", "primaryKey": false, "nullable": true,
 *       "comment": null }
 *   ],
 *   "indexes": ["idx_orders_customer", "idx_orders_status"]
 * }
 * </pre>
 *
 * <p>Downstream consumers of this object:
 * <ul>
 *   <li>{@code inspect_schema} tool – serializes List&lt;TableInfo&gt; directly to JSON for the AI agent</li>
 *   <li>{@code generate_mermaid_erd} tool – reads FK relationships from columns to build ERD edges</li>
 *   <li>{@code generate_documentation} tool – uses name, comment, and column metadata as documentation source</li>
 *   <li>{@code generate_java_dao} tool – uses column types and PK/FK flags to generate Entity and Repository classes</li>
 *   <li>{@code dependency_graph} tool – calls {@link #hasForeignKeys()} to identify which tables participate in relationships</li>
 * </ul>
 */
public class TableInfo {

    /** Table name as reported by the database. */
    private String name;

    /**
     * Database schema that contains this table.
     * For PostgreSQL this is typically "public".
     * For SQL Server this is typically "dbo".
     * For Oracle this is the owner/schema name.
     */
    private String schema;

    /**
     * Table comment/remark as defined in the database.
     * In legacy databases, table comments are rare but extremely valuable
     * when present. The AI agent uses this to understand the table's purpose
     * without having to infer it from column names alone.
     */
    private String comment;

    /**
     * Ordered list of columns in this table.
     * Order matches the ordinal position as reported by DatabaseMetaData.
     */
    private List<ColumnInfo> columns = new ArrayList<>();

    /**
     * Names of indexes defined on this table (excluding the primary key index).
     * The AI agent uses index information when analyzing query performance
     * and when running the query_plan_expert tool.
     */
    private List<String> indexes = new ArrayList<>();

    // --- Constructors ---

    public TableInfo() {
    }

    public TableInfo(String name, String schema) {
        this.name = name;
        this.schema = schema;
    }

    // --- Convenience methods ---

    /**
     * Adds a column to this table's column list.
     *
     * @param column the column to add
     */
    public void addColumn(ColumnInfo column) {
        this.columns.add(column);
    }

    /**
     * Adds an index name to this table's index list.
     *
     * @param indexName the name of the index
     */
    public void addIndex(String indexName) {
        this.indexes.add(indexName);
    }

    /**
     * Returns true if any column in this table is a foreign key.
     * Used by dependency_graph tool to determine which tables
     * participate in relationships.
     */
    public boolean hasForeignKeys() {
        return columns.stream().anyMatch(c -> c.getForeignKeyTable() != null);
    }

    // --- Getters and Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns;
    }

    public List<String> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<String> indexes) {
        this.indexes = indexes;
    }

    @Override
    public String toString() {
        return "TableInfo{name='" + name + "', schema='" + schema + "', columns=" + columns.size() + "}";
    }
}
