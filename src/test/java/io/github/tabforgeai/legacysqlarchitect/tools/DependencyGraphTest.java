package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyGraphTest extends BaseIntegrationTest {

    @Test
    void graphContainsAllThreeTables() throws Exception {
        McpSchema.CallToolResult result = new DependencyGraph(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        JsonNode root = json(result);
        JsonNode nodes = root.get("nodes");

        var nodeIds = StreamSupport.stream(nodes.spliterator(), false)
                .map(n -> n.get("id").asText())
                .toList();

        assertThat(nodeIds).contains("customers", "orders", "order_items");
    }

    @Test
    void fkEdgeExistsFromOrdersToCustomers() throws Exception {
        McpSchema.CallToolResult result = new DependencyGraph(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        JsonNode edges = json(result).get("edges");

        boolean hasFkEdge = StreamSupport.stream(edges.spliterator(), false)
                .anyMatch(e ->
                        "FK".equals(e.get("relationship").asText()) &&
                        "orders".equals(e.get("from").asText()) &&
                        "customers".equals(e.get("to").asText())
                );

        assertThat(hasFkEdge).as("FK edge orders → customers must exist").isTrue();
    }

    @Test
    void triggerEdgeExistsOnOrders() throws Exception {
        McpSchema.CallToolResult result = new DependencyGraph(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        JsonNode edges = json(result).get("edges");

        boolean hasTriggerEdge = StreamSupport.stream(edges.spliterator(), false)
                .anyMatch(e ->
                        "TRIGGER".equals(e.get("relationship").asText()) &&
                        "orders".equals(e.get("from").asText())
                );

        assertThat(hasTriggerEdge).as("TRIGGER edge from orders must exist").isTrue();
    }

    @Test
    void tableFilterLimitsGraph() throws Exception {
        McpSchema.CallToolResult result = new DependencyGraph(jdbcClient)
                .apply(null, req("schema", "test_schema", "table_filter", "order%"));

        assertThat(result.isError()).isFalse();

        JsonNode nodes = json(result).get("nodes");
        var nodeIds = StreamSupport.stream(nodes.spliterator(), false)
                .map(n -> n.get("id").asText())
                .toList();

        // Only order* tables should appear as primary nodes
        assertThat(nodeIds).anyMatch(id -> id.startsWith("order"));
    }
}
