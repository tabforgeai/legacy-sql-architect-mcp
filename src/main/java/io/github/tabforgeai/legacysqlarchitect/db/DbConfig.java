package io.github.tabforgeai.legacysqlarchitect.db;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Holds the database connection configuration loaded from config.json.
 *
 * The config.json file is read once at server startup by MCPServer.
 * An instance of this class is then passed to JdbcClient for establishing
 * the database connection.
 *
 * Supported db_type values:
 *   - "postgresql"  -> uses org.postgresql.Driver
 *                      db_schema: typically "public"; URL: jdbc:postgresql://host:5432/dbname
 *   - "sqlserver"   -> uses com.microsoft.sqlserver.jdbc.SQLServerDriver
 *                      db_schema: typically "dbo"; URL: jdbc:sqlserver://host:1433;databaseName=db
 *   - "oracle"      -> uses oracle.jdbc.OracleDriver
 *                      db_schema: the Oracle schema owner (UPPERCASE); URL variants:
 *                        - Service name:  jdbc:oracle:thin:@//host:1521/service_name
 *                        - SID (legacy):  jdbc:oracle:thin:@host:1521:SID
 *                      Note: Oracle stores object names in UPPERCASE by default.
 *                      Provide db_schema in uppercase (e.g. "MYSCHEMA") unless
 *                      objects were created with quoted lowercase names.
 *
 * Example config.json (PostgreSQL):
 * {
 *   "db_type": "postgresql",
 *   "db_url": "jdbc:postgresql://localhost:5432/mydb",
 *   "db_user": "myuser",
 *   "db_password": "mypassword",
 *   "db_schema": "public",
 *   "data_sampler_rows": 10,
 *   "data_sampler_mask_sensitive": true
 * }
 *
 * Example config.json (Oracle):
 * {
 *   "db_type": "oracle",
 *   "db_url": "jdbc:oracle:thin:@//localhost:1521/ORCL",
 *   "db_user": "myuser",
 *   "db_password": "mypassword",
 *   "db_schema": "MYSCHEMA",
 *   "data_sampler_rows": 10,
 *   "data_sampler_mask_sensitive": true
 * }
 */
public class DbConfig {

    /**
     * Database type identifier. Used to select the correct adapter
     * for database-specific SQL queries (e.g., EXPLAIN format, system catalogs).
     * Valid values: "postgresql", "sqlserver"
     */
    @JsonProperty("db_type")
    private String dbType;

    /**
     * Full JDBC connection URL.
     * PostgreSQL example: "jdbc:postgresql://localhost:5432/mydb"
     * SQL Server example: "jdbc:sqlserver://localhost:1433;databaseName=mydb"
     */
    @JsonProperty("db_url")
    private String dbUrl;

    /** Database username for authentication. */
    @JsonProperty("db_user")
    private String dbUser;

    /** Database password for authentication. */
    @JsonProperty("db_password")
    private String dbPassword;

    /**
     * Default schema to inspect.
     * For PostgreSQL: "public"
     * For SQL Server: "dbo"
     * If null or empty, all schemas visible to the user will be inspected.
     */
    @JsonProperty("db_schema")
    private String dbSchema;

    /**
     * Number of sample rows returned by the data_sampler tool.
     * Default: 10. Keep this small to avoid sending large payloads to the AI agent.
     */
    @JsonProperty("data_sampler_rows")
    private int dataSamplerRows = 10;

    /**
     * When true, the data_sampler tool replaces values in columns whose names
     * suggest sensitive data (e.g., email, password, ssn, phone, credit_card)
     * with masked placeholders like "***MASKED***".
     * Recommended: true for production databases.
     */
    @JsonProperty("data_sampler_mask_sensitive")
    private boolean dataSamplerMaskSensitive = true;

    // --- Getters and Setters ---

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public void setDbUrl(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public String getDbSchema() {
        return dbSchema;
    }

    public void setDbSchema(String dbSchema) {
        this.dbSchema = dbSchema;
    }

    public int getDataSamplerRows() {
        return dataSamplerRows;
    }

    public void setDataSamplerRows(int dataSamplerRows) {
        this.dataSamplerRows = dataSamplerRows;
    }

    public boolean isDataSamplerMaskSensitive() {
        return dataSamplerMaskSensitive;
    }

    public void setDataSamplerMaskSensitive(boolean dataSamplerMaskSensitive) {
        this.dataSamplerMaskSensitive = dataSamplerMaskSensitive;
    }
}
