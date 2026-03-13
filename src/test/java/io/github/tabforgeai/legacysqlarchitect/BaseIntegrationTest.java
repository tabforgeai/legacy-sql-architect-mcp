package io.github.tabforgeai.legacysqlarchitect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tabforgeai.legacysqlarchitect.db.DbConfig;
import io.github.tabforgeai.legacysqlarchitect.db.JdbcClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for all MCP tool integration tests.
 *
 * Starts an embedded PostgreSQL instance once per test class, creates the
 * test schema defined in {@link TestSchema}, and provides a ready-to-use
 * {@link JdbcClient} configured identically to production (read-only connection,
 * db_type="postgresql").
 *
 * Subclasses inherit the {@code jdbcClient} field and the {@code req()} / {@code text()}
 * helpers — they only need to instantiate their specific tool and call {@code apply()}.
 *
 * The embedded PostgreSQL binary is downloaded as a Maven artifact the first
 * time {@code mvn test} is run. No Docker, no external installation required.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Embedded PostgreSQL process — started once, shared across all tests in the subclass. */
    private EmbeddedPostgres embeddedPostgres;

    /**
     * Ready-to-use JdbcClient connected to the embedded PostgreSQL.
     * Connection is read-only (same as production).
     * Available to all subclass test methods.
     */
    protected JdbcClient jdbcClient;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @BeforeAll
    void startDatabase() throws Exception {
        embeddedPostgres = EmbeddedPostgres.start();

        // Use a plain writable connection for DDL setup.
        // JdbcClient sets connection.setReadOnly(true), so we must create the
        // schema before handing the connection to JdbcClient.
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : TestSchema.STATEMENTS) {
                stmt.execute(sql);
            }
        }

        // Build JdbcClient the same way the production server does.
        DbConfig config = new DbConfig();
        config.setDbType("postgresql");
        config.setDbUrl(embeddedPostgres.getJdbcUrl("postgres", "postgres"));
        config.setDbUser("postgres");
        config.setDbPassword("postgres");
        config.setDbSchema(TestSchema.SCHEMA);
        config.setDataSamplerRows(5);
        config.setDataSamplerMaskSensitive(false);

        jdbcClient = new JdbcClient(config);
        jdbcClient.connect();
    }

    @AfterAll
    void stopDatabase() throws Exception {
        if (jdbcClient != null) jdbcClient.close();
        if (embeddedPostgres != null) embeddedPostgres.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a CallToolRequest with the given key-value argument pairs.
     *
     * Usage: {@code req("schema", "test_schema", "table", "orders")}
     *
     * @param keyValuePairs alternating key (String) and value (Object) pairs
     * @return a CallToolRequest ready to pass to a tool's apply() method
     */
    protected McpSchema.CallToolRequest req(Object... keyValuePairs) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            args.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new McpSchema.CallToolRequest("test", args);
    }

    /**
     * Extracts the text content from a CallToolResult.
     *
     * All nine MCP tools return exactly one TextContent item. This helper
     * unwraps it so tests can work directly with the JSON string.
     *
     * @param result the tool result to extract text from
     * @return the text content string (typically a JSON object)
     */
    protected String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        McpSchema.TextContent tc = (McpSchema.TextContent) result.content().get(0);
        return tc.text();
    }

    /**
     * Parses the text content of a CallToolResult as JSON.
     *
     * @param result the tool result whose text content is a JSON string
     * @return parsed JsonNode for field-level assertions
     */
    protected JsonNode json(McpSchema.CallToolResult result) throws Exception {
        return JSON.readTree(text(result));
    }
}
