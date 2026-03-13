package io.github.tabforgeai.legacysqlarchitect.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Manages a single JDBC connection to the target database.
 *
 * This class is intentionally simple: it holds one shared connection that is
 * established at server startup and reused by all tools throughout the server
 * lifetime. This is appropriate because:
 *   - The MCP server handles one request at a time (stdio transport is sequential)
 *   - Legacy databases often have strict connection limits
 *   - Connection pooling would add unnecessary complexity for this use case
 *
 * If the connection drops (e.g., database restart), the server must be restarted.
 * This is an acceptable trade-off for a developer tool.
 *
 * Usage:
 *   JdbcClient client = new JdbcClient(config);
 *   client.connect();
 *   Connection conn = client.getConnection();
 *   // ... use conn in tools ...
 *   client.close();
 */
public class JdbcClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcClient.class);

    /** Configuration loaded from config.json. */
    private final DbConfig config;

    /**
     * The active JDBC connection.
     * Null until connect() is called successfully.
     */
    private Connection connection;

    /**
     * Creates a new JdbcClient with the given configuration.
     * Does not open the connection - call connect() explicitly.
     *
     * @param config database connection configuration from config.json
     */
    public JdbcClient(DbConfig config) {
        this.config = config;
    }

    /**
     * Establishes the JDBC connection to the target database.
     *
     * The JDBC driver is loaded automatically via the service loader mechanism
     * (postgresql, mssql-jdbc, and ojdbc11 all register via META-INF/services).
     * No explicit Class.forName() call is needed.
     *
     * @throws SQLException if the connection cannot be established.
     *         Common causes: wrong URL, wrong credentials, database not reachable,
     *         firewall blocking the port.
     */
    public void connect() throws SQLException {
        log.info("Connecting to database: type={}, url={}", config.getDbType(), config.getDbUrl());
        connection = DriverManager.getConnection(
                config.getDbUrl(),
                config.getDbUser(),
                config.getDbPassword()
        );
        // Set read-only mode as a safety measure.
        // All tools only read data; none of them should modify the database.
        // This prevents accidental writes if a bug causes malformed SQL to execute.
        connection.setReadOnly(true);
        log.info("Database connection established successfully.");
    }

    /**
     * Returns the active JDBC connection for use by tool implementations.
     *
     * Tools should NOT close this connection after use - it is shared and
     * managed by the server lifecycle. Tools should only close their own
     * PreparedStatement and ResultSet objects.
     *
     * @return the active Connection
     * @throws IllegalStateException if connect() has not been called yet
     */
    public Connection getConnection() {
        if (connection == null) {
            throw new IllegalStateException("JdbcClient.connect() must be called before getConnection()");
        }
        return connection;
    }

    /**
     * Returns the database configuration. Used by tools that need to know
     * the db_type for database-specific query logic (e.g., different EXPLAIN
     * syntax for PostgreSQL vs SQL Server).
     *
     * @return the DbConfig instance
     */
    public DbConfig getConfig() {
        return config;
    }

    /**
     * Closes the database connection.
     * Called during server shutdown. Safe to call even if connect() was never
     * called or if the connection is already closed.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database connection closed.");
            } catch (SQLException e) {
                // Log but do not re-throw - we are shutting down anyway.
                log.warn("Error while closing database connection: {}", e.getMessage());
            }
        }
    }
}
