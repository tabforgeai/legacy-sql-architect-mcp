package io.github.tabforgeai.legacysqlarchitect.tools;

import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateMermaidErdTest extends BaseIntegrationTest {

    // generate_mermaid_erd returns plain Mermaid text (not JSON).
    // Table names in the diagram are UPPERCASE (sanitizeName() converts to uppercase).

    @Test
    void outputWrappedInMermaidFence() {
        McpSchema.CallToolResult result = new GenerateMermaidErd(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        String output = text(result);
        assertThat(output).startsWith("```mermaid");
        assertThat(output.trim()).endsWith("```");
    }

    @Test
    void diagramContainsErDiagramKeyword() {
        McpSchema.CallToolResult result = new GenerateMermaidErd(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("erDiagram");
    }

    @Test
    void diagramContainsAllThreeTables() {
        McpSchema.CallToolResult result = new GenerateMermaidErd(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        // sanitizeName() converts to UPPERCASE in the Mermaid output
        String output = text(result).toUpperCase();
        assertThat(output).contains("CUSTOMERS");
        assertThat(output).contains("ORDERS");
        assertThat(output).contains("ORDER_ITEMS");
    }

    @Test
    void diagramContainsForeignKeyRelationship() {
        McpSchema.CallToolResult result = new GenerateMermaidErd(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();
        // Mermaid ERD FK notation uses ||--o{ or similar
        assertThat(text(result)).containsAnyOf("||--", "}|--", "|{--", "--o{", "--||");
    }
}
