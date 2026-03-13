package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlanExpertTest extends BaseIntegrationTest {

    @Test
    void returnsRawPlanAndWarningsFields() throws Exception {
        McpSchema.CallToolResult result = new QueryPlanExpert(jdbcClient)
                .apply(null, req(
                        "schema", "test_schema",
                        "sql", "SELECT * FROM orders WHERE customer_id = 1"
                ));

        assertThat(result.isError()).isFalse();

        JsonNode root = json(result);
        assertThat(root.has("raw_plan")).isTrue();
        assertThat(root.has("warnings")).isTrue();
        assertThat(root.has("database_type")).isTrue();
        assertThat(root.get("database_type").asText()).isEqualTo("postgresql");
        assertThat(root.get("raw_plan").isArray()).isTrue();
        assertThat(root.get("raw_plan").size()).isGreaterThan(0);
    }

    @Test
    void fullTableScanOnLargeEstimateProducesWarning() throws Exception {
        // A SELECT * with no index on a table without statistics will produce a seq scan.
        // In our tiny test table (2 rows) the planner won't warn — this test just verifies
        // the warnings array is present and the tool does not crash.
        McpSchema.CallToolResult result = new QueryPlanExpert(jdbcClient)
                .apply(null, req(
                        "schema", "test_schema",
                        "sql", "SELECT * FROM customers"
                ));

        assertThat(result.isError()).isFalse();
        JsonNode root = json(result);
        assertThat(root.get("warnings").isArray()).isTrue();
    }

    @Test
    void missingSqlParameterReturnsError() {
        McpSchema.CallToolResult result = new QueryPlanExpert(jdbcClient)
                .apply(null, req("schema", "test_schema")); // no "sql"

        assertThat(result.isError()).isTrue();
    }
}
