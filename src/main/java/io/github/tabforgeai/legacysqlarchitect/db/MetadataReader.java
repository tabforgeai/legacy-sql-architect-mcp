package io.github.tabforgeai.legacysqlarchitect.db;

import io.github.tabforgeai.legacysqlarchitect.model.ColumnInfo;
import io.github.tabforgeai.legacysqlarchitect.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Reads structural metadata from the database using JDBC DatabaseMetaData.
 *
 * This class is the core of the inspect_schema tool. It uses only standard
 * JDBC DatabaseMetaData API, which means it works across all supported databases
 * (PostgreSQL, SQL Server) without any database-specific SQL queries.
 *
 * DatabaseMetaData is a read-only, connection-level API - it does not execute
 * user queries and does not affect the database in any way.
 *
 * All public methods accept an explicit schema parameter rather than reading
 * it from config. This allows the AI agent to inspect a specific schema on demand
 * (via the inspect_schema tool's optional schema parameter).
 */
public class MetadataReader {

    private static final Logger log = LoggerFactory.getLogger(MetadataReader.class);

    /** JDBC connection - shared, read-only, managed by JdbcClient. */
    private final Connection connection;

    /**
     * Creates a new MetadataReader using the given JDBC connection.
     *
     * @param connection an active, read-only JDBC connection from JdbcClient
     */
    public MetadataReader(Connection connection) {
        this.connection = connection;
    }

    /**
     * Reads all tables in the specified schema, including full column metadata,
     * primary keys, foreign keys, index names, and table comments.
     *
     * This is the main method called by the inspect_schema tool. The returned
     * list is sorted alphabetically by table name for consistent AI output.
     *
     * @param schema the database schema to inspect (e.g., "public", "dbo").
     *               Must not be null.
     * @param tableNamePattern optional SQL LIKE pattern to filter tables
     *                         (e.g., "ORD%" returns only tables starting with ORD).
     *                         Pass null or "%" to return all tables.
     * @return alphabetically sorted list of TableInfo objects with full metadata
     * @throws SQLException if the database reports an error during metadata retrieval
     */
    public List<TableInfo> readTables(String schema, String tableNamePattern) throws SQLException {
        log.info("Reading table metadata: schema={}, filter={}", schema, tableNamePattern);

        DatabaseMetaData meta = connection.getMetaData();
        String pattern = (tableNamePattern == null || tableNamePattern.isBlank()) ? "%" : tableNamePattern;

        // getTables() returns: TABLE_CAT, TABLE_SCHEM, TABLE_NAME, TABLE_TYPE, REMARKS, ...
        // We filter for TABLE_TYPE = "TABLE" to exclude views, system tables, etc.
        List<TableInfo> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, schema, pattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableComment = rs.getString("REMARKS");

                TableInfo table = new TableInfo(tableName, tableSchema);
                table.setComment(tableComment);
                tables.add(table);
            }
        }

        log.info("Found {} tables in schema '{}'", tables.size(), schema);

        // Enrich each table with columns, primary keys, foreign keys, and indexes.
        // Each of these is a separate DatabaseMetaData call.
        for (TableInfo table : tables) {
            enrichWithColumns(meta, table);
            enrichWithPrimaryKeys(meta, table);
            enrichWithForeignKeys(meta, table);
            enrichWithIndexes(meta, table);
        }

        // Sort alphabetically so the AI agent receives a consistent, predictable list
        tables.sort(Comparator.comparing(TableInfo::getName, String.CASE_INSENSITIVE_ORDER));

        return tables;
    }

    /**
     * Populates the column list for a given table using DatabaseMetaData.getColumns().
     *
     * Column order is preserved as reported by the database (ordinal position).
     * Each column gets its name, SQL type name, size, and nullable flag.
     * Column comments (REMARKS) are included when present.
     *
     * @param meta  the DatabaseMetaData from the current connection
     * @param table the TableInfo to populate with columns
     * @throws SQLException if the database reports an error
     */
    private void enrichWithColumns(DatabaseMetaData meta, TableInfo table) throws SQLException {
        // getColumns() returns one row per column, ordered by ORDINAL_POSITION.
        // Key fields: COLUMN_NAME, TYPE_NAME, COLUMN_SIZE, IS_NULLABLE, REMARKS
        try (ResultSet rs = meta.getColumns(null, table.getSchema(), table.getName(), "%")) {
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo();
                col.setName(rs.getString("COLUMN_NAME"));
                col.setType(rs.getString("TYPE_NAME"));
                col.setSize(rs.getInt("COLUMN_SIZE"));
                col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                col.setComment(rs.getString("REMARKS"));
                table.addColumn(col);
            }
        }
    }

    /**
     * Marks the primary key column(s) of a table by setting ColumnInfo.primaryKey = true.
     *
     * Uses DatabaseMetaData.getPrimaryKeys() which returns the PK columns by name.
     * We then find the matching ColumnInfo objects in the table and flag them.
     *
     * Composite primary keys (multiple columns) are fully supported -
     * each column in the composite key will be flagged individually.
     *
     * @param meta  the DatabaseMetaData from the current connection
     * @param table the TableInfo whose columns should be marked as PK where applicable
     * @throws SQLException if the database reports an error
     */
    private void enrichWithPrimaryKeys(DatabaseMetaData meta, TableInfo table) throws SQLException {
        // Build a Set of PK column names for O(1) lookup
        Set<String> pkColumns = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, table.getSchema(), table.getName())) {
            while (rs.next()) {
                pkColumns.add(rs.getString("COLUMN_NAME"));
            }
        }

        // Mark matching columns in the table's column list
        for (ColumnInfo col : table.getColumns()) {
            if (pkColumns.contains(col.getName())) {
                col.setPrimaryKey(true);
            }
        }
    }

    /**
     * Populates foreign key information for columns that reference other tables.
     *
     * Uses DatabaseMetaData.getImportedKeys() which returns all FK relationships
     * where this table is the "child" (i.e., columns in THIS table reference
     * columns in OTHER tables). This is the direction that matters for the AI
     * agent when understanding data relationships.
     *
     * For each FK column, we set:
     *   - foreignKeyTable: the table being referenced (e.g., "customers")
     *   - foreignKeyColumn: the specific column in that table (e.g., "id")
     *
     * @param meta  the DatabaseMetaData from the current connection
     * @param table the TableInfo whose columns should be enriched with FK info
     * @throws SQLException if the database reports an error
     */
    private void enrichWithForeignKeys(DatabaseMetaData meta, TableInfo table) throws SQLException {
        // getImportedKeys() returns FK relationships: FKCOLUMN_NAME -> PKTABLE_NAME.PKCOLUMN_NAME
        // Build a map of: local column name -> referenced "table.column"
        Map<String, String[]> fkMap = new HashMap<>();
        try (ResultSet rs = meta.getImportedKeys(null, table.getSchema(), table.getName())) {
            while (rs.next()) {
                String fkColumnName  = rs.getString("FKCOLUMN_NAME");
                String pkTableName   = rs.getString("PKTABLE_NAME");
                String pkColumnName  = rs.getString("PKCOLUMN_NAME");
                fkMap.put(fkColumnName, new String[]{pkTableName, pkColumnName});
            }
        }

        // Set FK fields on matching ColumnInfo objects
        for (ColumnInfo col : table.getColumns()) {
            String[] ref = fkMap.get(col.getName());
            if (ref != null) {
                col.setForeignKeyTable(ref[0]);
                col.setForeignKeyColumn(ref[1]);
            }
        }
    }

    /**
     * Collects the names of non-primary-key indexes defined on a table.
     *
     * Index information is important context for the query_plan_expert tool
     * and for the AI agent when reasoning about query performance.
     * We exclude the primary key index to avoid redundancy (PKs are already
     * marked on individual columns).
     *
     * Note: Some databases report the same index name multiple times (once per
     * indexed column for composite indexes). We use a Set to deduplicate.
     *
     * @param meta  the DatabaseMetaData from the current connection
     * @param table the TableInfo to populate with index names
     * @throws SQLException if the database reports an error
     */
    private void enrichWithIndexes(DatabaseMetaData meta, TableInfo table) throws SQLException {
        // getIndexInfo() parameters: unique=false (return all), approximate=true (faster)
        Set<String> indexNames = new LinkedHashSet<>();
        try (ResultSet rs = meta.getIndexInfo(null, table.getSchema(), table.getName(), false, true)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                // INDEX_NAME can be null for table statistics rows returned by some drivers
                if (indexName != null) {
                    indexNames.add(indexName);
                }
            }
        }

        // Remove the primary key index - it is redundant since PK columns are already flagged
        // Primary key index names are typically: "pk_<table>", "<table>_pkey", etc.
        // We use a heuristic: remove entries that contain "pkey" or "pk_" (case-insensitive).
        // This is best-effort; the goal is to keep the index list clean for the AI agent.
        indexNames.removeIf(name -> name.toLowerCase().contains("pkey")
                || name.toLowerCase().startsWith("pk_"));

        indexNames.forEach(table::addIndex);
    }
}
