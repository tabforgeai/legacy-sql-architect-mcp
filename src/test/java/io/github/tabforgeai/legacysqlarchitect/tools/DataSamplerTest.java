package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataSamplerTest extends BaseIntegrationTest {

    @Test
    void samplesRowsFromCustomers() throws Exception {
        McpSchema.CallToolResult result = new DataSampler(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "customers"));

        assertThat(result.isError()).isFalse();

        JsonNode root = json(result);
        assertThat(root.get("table").asText()).isEqualTo("customers");
        assertThat(root.get("rows").isArray()).isTrue();
        assertThat(root.get("rows").size()).isGreaterThan(0);
        assertThat(root.get("rows").size()).isLessThanOrEqualTo(5); // configured limit
    }

    @Test
    void rowsContainExpectedColumns() throws Exception {
        McpSchema.CallToolResult result = new DataSampler(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "customers"));

        JsonNode firstRow = json(result).get("rows").get(0);

        // Every row must have the columns we defined in TestSchema
        assertThat(firstRow.has("id")).isTrue();
        assertThat(firstRow.has("full_name")).isTrue();
        assertThat(firstRow.has("email")).isTrue();
    }

    @Test
    void missingTableParameterReturnsError() {
        McpSchema.CallToolResult result = new DataSampler(jdbcClient)
                .apply(null, req("schema", "test_schema")); // no "table"

        assertThat(result.isError()).isTrue();
    }
}
