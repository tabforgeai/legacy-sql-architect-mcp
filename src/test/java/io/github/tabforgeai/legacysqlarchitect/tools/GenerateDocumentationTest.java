package io.github.tabforgeai.legacysqlarchitect.tools;

import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateDocumentationTest extends BaseIntegrationTest {

    @Test
    void outputContainsAllTableNames() {
        McpSchema.CallToolResult result = new GenerateDocumentation(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        String output = text(result);
        assertThat(output).containsIgnoringCase("customers");
        assertThat(output).containsIgnoringCase("orders");
        assertThat(output).containsIgnoringCase("order_items");
    }

    @Test
    void outputContainsColumnNames() {
        McpSchema.CallToolResult result = new GenerateDocumentation(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        String output = text(result);
        // Key columns from our schema must appear in the documentation
        assertThat(output).containsIgnoringCase("full_name");
        assertThat(output).containsIgnoringCase("customer_id");
        assertThat(output).containsIgnoringCase("product_code");
    }

    @Test
    void singleTableDocumentationIsFocused() {
        McpSchema.CallToolResult result = new GenerateDocumentation(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "orders"));

        assertThat(result.isError()).isFalse();

        String output = text(result);
        assertThat(output).containsIgnoringCase("orders");
        // Must contain orders' columns
        assertThat(output).containsIgnoringCase("status");
        assertThat(output).containsIgnoringCase("total");
    }
}
