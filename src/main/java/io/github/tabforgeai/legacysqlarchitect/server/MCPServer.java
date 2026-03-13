package io.github.tabforgeai.legacysqlarchitect.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tabforgeai.legacysqlarchitect.db.DbConfig;
import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.github.tabforgeai.legacysqlarchitect.tools.DataSampler;
import io.github.tabforgeai.legacysqlarchitect.tools.DependencyGraph;
import io.github.tabforgeai.legacysqlarchitect.tools.FindImpact;
import io.github.tabforgeai.legacysqlarchitect.tools.GenerateDocumentation;
import io.github.tabforgeai.legacysqlarchitect.tools.GenerateJavaDao;
import io.github.tabforgeai.legacysqlarchitect.tools.GenerateMermaidErd;
import io.github.tabforgeai.legacysqlarchitect.tools.GetProcedureSource;
import io.github.tabforgeai.legacysqlarchitect.tools.InspectSchema;
import io.github.tabforgeai.legacysqlarchitect.tools.QueryPlanExpert;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.file.Paths;

/**
 * Entry point for the Legacy SQL Architect MCP server.
 *
 * Startup sequence:
 *   1. Load config.json (from working directory, or bundled default as fallback)
 *   2. Connect to the database via JdbcClient
 *   3. Register all MCP tools
 *   4. Start the MCP server on stdio transport and block until shutdown
 *
 * The server uses stdio transport, which means:
 *   - Input  (MCP requests)  arrives on STDIN
 *   - Output (MCP responses) is sent to STDOUT
 *   - All logs go to STDERR (configured in logback.xml)
 *
 * Claude Desktop / Cursor config example:
 * {
 *   "mcpServers": {
 *     "legacy-sql-architect": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/legacy-sql-architect-mcp.jar"]
 *     }
 *   }
 * }
 *
 * Or with the native installer (bundled JRE):
 * {
 *   "mcpServers": {
 *     "legacy-sql-architect": {
 *       "command": "/path/to/legacy-sql-architect"
 *     }
 *   }
 * }
 */
public class MCPServer {

    /*
     * Resolve the log directory and publish it as APP_LOGS system property BEFORE
     * Logback initializes. Logback initializes lazily on the first LoggerFactory.getLogger()
     * call (the line below this block), so this static initializer runs just in time.
     *
     * Resolution strategy (in order of priority):
     *
     *   1. "app.dir" system property — set automatically by jpackage native launchers.
     *      Points to <install>\app\ on Windows (e.g., D:\LegacySQLArchitectMCP\app\).
     *      We go one level up to place logs in <install>\logs\.
     *
     *   2. JAR location via ProtectionDomain.getCodeSource() — more reliable fallback.
     *      When running from the jpackage-installed EXE, the fat JAR is at:
     *        <install>\app\legacy-sql-architect-mcp.jar
     *      Two getParent() calls reach the installation root, then we append \logs\.
     *      We only use this path if the code source is actually a .jar file (not a
     *      directory), to avoid interfering with Maven/IDE class-directory launches.
     *
     *   3. Relative "logs" — fallback for Maven, IDE, and any other launch method.
     *      Creates a logs\ folder relative to the current working directory.
     */
    static {
        String logDir = "logs"; // default: relative to working directory

        // Priority 1: app.dir set by jpackage launcher
        String appDir = System.getProperty("app.dir");
        if (appDir != null && !appDir.isBlank()) {
            logDir = java.nio.file.Paths.get(appDir).getParent().resolve("logs").toString();
        } else {
            // Priority 2: detect install root from the running JAR's location
            try {
                java.security.CodeSource cs =
                        MCPServer.class.getProtectionDomain().getCodeSource();
                if (cs != null) {
                    java.nio.file.Path jarPath =
                            java.nio.file.Paths.get(cs.getLocation().toURI());
                    // Only treat it as an installed JAR if the path actually ends with .jar.
                    // In Maven/IDE, the source is a classes/ directory — we skip it here.
                    if (jarPath.toString().endsWith(".jar")) {
                        // <install>\app\legacy-sql-architect-mcp.jar
                        //   .getParent() → <install>\app\
                        //   .getParent() → <install>\
                        //   .resolve("logs") → <install>\logs\
                        logDir = jarPath.getParent().getParent().resolve("logs").toString();
                    }
                }
            } catch (Exception ignored) {
                // Silently fall through to the relative-path default
            }
        }

        System.setProperty("APP_LOGS", logDir);
    }

    private static final Logger log = LoggerFactory.getLogger(MCPServer.class);

    /** Server name reported to the MCP client during the initialize handshake. */
    private static final String SERVER_NAME = "legacy-sql-architect-mcp";

    /** Server version reported to the MCP client. */
    private static final String SERVER_VERSION = "1.0.0";

    /**
     * Name of the config file. The server first looks for this file in the
     * current working directory. If not found, it falls back to the bundled
     * default inside the JAR (src/main/resources/config.json).
     */
    private static final String CONFIG_FILE = "config.json";

    /**
     * Server entry point. Executes the full startup sequence and then blocks
     * until the MCP client disconnects or the process is terminated.
     *
     * @param args command-line arguments (not used; configuration is read from config.json)
     */
    public static void main(String[] args) {
        log.info("Starting {} v{}", SERVER_NAME, SERVER_VERSION);

        // --- Step 1: Load configuration ---
        DbConfig config = loadConfig();
        if (config == null) {
            // loadConfig() already logged the error
            System.exit(1);
        }

        // --- Step 2: Create JdbcClient (no connection yet) ---
        // Connection happens AFTER the MCP server starts listening, so we respond
        // to the MCP client's "initialize" handshake before the DB roundtrip.
        // Claude Desktop has a short timeout (~1s); if we connect first the server
        // starts too late and the client closes the pipe before we are ready.
        JdbcClient jdbcClient = new JdbcClient(config);

        // Latch that keeps the main thread alive until the process is killed.
        // closeGracefully() returns too early (after the initialize+tools/list
        // handshake), leaving no active session for subsequent tool calls.
        // Using a latch ensures the process stays alive — it is released only by
        // the shutdown hook when SIGTERM arrives (e.g. Claude Desktop closing).
        java.util.concurrent.CountDownLatch shutdownLatch = new java.util.concurrent.CountDownLatch(1);

        // Register a shutdown hook to close the DB connection cleanly
        // when the process receives SIGTERM (e.g., when the MCP client shuts down).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Closing database connection.");
            jdbcClient.close();
            shutdownLatch.countDown();
        }));

        // --- Step 3: Create the JSON mapper and stdio transport ---
        // JacksonMcpJsonMapperSupplier provides a pre-configured Jackson 3 based
        // McpJsonMapper. This is the standard way to set up the MCP SDK serialization.
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        // AllVersionsStdioTransport overrides protocolVersions() to advertise all known
        // MCP protocol versions. The base StdioServerTransportProvider only advertises
        // "2024-11-05", which causes modern MCP clients (Claude Desktop 2025+) to reject
        // the connection because they send "2025-11-25" in the initialize handshake.
        StdioServerTransportProvider transport = new AllVersionsStdioTransport(jsonMapper);

        // --- Step 4: Build the MCP server and register tools ---
        // build() calls setSessionFactory() on the transport, which starts the stdin
        // reader thread immediately — the server is ready to handle "initialize" at
        // this point, well within Claude Desktop's connection timeout.
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .toolCall(InspectSchema.toolDefinition(jsonMapper),      new InspectSchema(jdbcClient))
                .toolCall(DataSampler.toolDefinition(jsonMapper),        new DataSampler(jdbcClient))
                .toolCall(GetProcedureSource.toolDefinition(jsonMapper), new GetProcedureSource(jdbcClient))
                .toolCall(QueryPlanExpert.toolDefinition(jsonMapper),    new QueryPlanExpert(jdbcClient))
                .toolCall(DependencyGraph.toolDefinition(jsonMapper),    new DependencyGraph(jdbcClient))
                .toolCall(GenerateMermaidErd.toolDefinition(jsonMapper),    new GenerateMermaidErd(jdbcClient))
                .toolCall(GenerateDocumentation.toolDefinition(jsonMapper), new GenerateDocumentation(jdbcClient))
                .toolCall(FindImpact.toolDefinition(jsonMapper),           new FindImpact(jdbcClient))
                .toolCall(GenerateJavaDao.toolDefinition(jsonMapper),      new GenerateJavaDao(jdbcClient))
                .build();

        log.info("MCP server started. Listening for requests on stdio.");

        // --- Step 4b: Connect to the database now ---
        // The MCP server is already listening; the initialize handshake has time to
        // complete while we open the JDBC connection. Any tool call will arrive only
        // after the handshake, by which time the connection will be ready.
        try {
            jdbcClient.connect();
        } catch (Exception e) {
            log.error("Failed to connect to database: {}", e.getMessage());
            log.error("Check db_url, db_user, db_password in config.json and verify the database is reachable.");
            server.close();
            System.exit(1);
        }

        // --- Step 5: Block until SIGTERM ---
        // The MCP SDK background threads handle all requests. Main thread just waits.
        // shutdownLatch is released by the shutdown hook on SIGTERM/SIGINT.
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Server shut down.");
    }

    /**
     * Loads the database configuration from config.json.
     *
     * Resolution order:
     *   1. config.json in the current working directory
     *      → covers: running the JAR directly from a terminal, MCP client that sets CWD
     *   2. config.json in the same directory as the running JAR
     *      → covers: jpackage native installer (the JAR and config.json both live in
     *        the installation's "app\" subdirectory, but the working directory when the
     *        EXE is launched by Claude Desktop or Windows is the user's home directory,
     *        NOT the installation directory)
     *   3. config.json bundled inside the JAR (template/default)
     *      → last resort fallback; contains placeholder values and will fail to connect
     *
     * @return a populated DbConfig, or null if the file cannot be found or parsed
     */
    private static DbConfig loadConfig() {
        ObjectMapper mapper = new ObjectMapper();

        // Location 1: current working directory
        Path externalConfig = Paths.get(CONFIG_FILE);
        if (Files.exists(externalConfig)) {
            try {
                DbConfig config = mapper.readValue(externalConfig.toFile(), DbConfig.class);
                log.info("Configuration loaded from: {}", externalConfig.toAbsolutePath());
                return config;
            } catch (Exception e) {
                log.error("Failed to parse config.json from working directory: {}", e.getMessage());
                return null;
            }
        }

        // Location 2: directory containing the running JAR file.
        // When installed via jpackage, the JAR lives in <install>\app\ and config.json
        // is placed there alongside it. The working directory at runtime is typically
        // the user's home directory, so we must resolve the JAR location explicitly.
        Path jarDirConfig = resolveJarDirConfig();
        if (jarDirConfig != null && Files.exists(jarDirConfig)) {
            try {
                DbConfig config = mapper.readValue(jarDirConfig.toFile(), DbConfig.class);
                log.info("Configuration loaded from JAR directory: {}", jarDirConfig.toAbsolutePath());
                return config;
            } catch (Exception e) {
                log.error("Failed to parse config.json from JAR directory: {}", e.getMessage());
                return null;
            }
        }

        // Location 3: bundled template inside the JAR (placeholder values — will not connect)
        try (InputStream is = MCPServer.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                log.error("config.json not found. Place a config.json file next to the executable or in the working directory.");
                return null;
            }
            DbConfig config = mapper.readValue(is, DbConfig.class);
            log.warn("Using bundled default config.json. Edit config.json in the installation directory to configure your database.");
            return config;
        } catch (Exception e) {
            log.error("Failed to parse bundled config.json: {}", e.getMessage());
            return null;
        }
    }

    /**
     * StdioServerTransportProvider subclass that advertises all known MCP protocol
     * versions instead of only "2024-11-05".
     *
     * Claude Desktop 2025+ sends protocolVersion "2025-11-25" in the initialize
     * handshake. When the server's protocolVersions() list does not contain that
     * version, the SDK session rejects the connection and closes immediately.
     * This subclass overrides the method to return the full version list so that
     * the session can negotiate to whichever version the client prefers.
     */
    private static class AllVersionsStdioTransport extends StdioServerTransportProvider {

        AllVersionsStdioTransport(McpJsonMapper jsonMapper) {
            super(jsonMapper);
        }

        @Override
        public List<String> protocolVersions() {
            return List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
        }
    }

    /**
     * Resolves the path to config.json in the same directory as the running JAR.
     *
     * This handles the jpackage native installer layout where the JAR and config.json
     * are both placed in the "app\" subdirectory of the installation, for example:
     *   C:\Program Files\LegacySQLArchitectMCP\app\legacy-sql-architect-mcp.jar
     *   C:\Program Files\LegacySQLArchitectMCP\app\config.json
     *
     * The JAR location is obtained via ProtectionDomain.getCodeSource(), which returns
     * the URL of the JAR (or class directory when running from an IDE).
     *
     * Returns null if the JAR location cannot be determined (e.g. in some class loader
     * configurations or when running tests), so callers must handle null safely.
     *
     * @return Path to config.json in the JAR's directory, or null if not resolvable
     */
    private static Path resolveJarDirConfig() {
        try {
            Path jarPath = Paths.get(
                    MCPServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            // jarPath points to the JAR file itself; .getParent() gives the containing directory
            return jarPath.getParent().resolve(CONFIG_FILE);
        } catch (Exception e) {
            // ProtectionDomain can return null in certain restricted environments;
            // log at debug level only since this is a non-critical fallback path
            log.debug("Could not resolve JAR directory for config lookup: {}", e.getMessage());
            return null;
        }
    }
}
