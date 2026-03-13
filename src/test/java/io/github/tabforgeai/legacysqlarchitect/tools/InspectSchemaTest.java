package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class InspectSchemaTest extends BaseIntegrationTest {

    // inspect_schema returns a JSON array of table objects directly.
    // Each element has field "name" (not "table_name").

    @Test
    void returnsAllThreeTables() throws Exception {
        McpSchema.CallToolResult result = new InspectSchema(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        JsonNode tables = json(result); // top-level is the array
        assertThat(tables.isArray()).isTrue();
        assertThat(tables.size()).isGreaterThanOrEqualTo(3);

        var tableNames = StreamSupport.stream(tables.spliterator(), false)
                .map(t -> t.get("name").asText())
                .toList();

        assertThat(tableNames).contains("customers", "orders", "order_items");
    }

    @Test
    void ordersTableHasForeignKeyColumn() throws Exception {
        McpSchema.CallToolResult result = new InspectSchema(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "orders"));

        assertThat(result.isError()).isFalse();

        JsonNode tables = json(result);
        JsonNode ordersTable = StreamSupport.stream(tables.spliterator(), false)
                .filter(t -> "orders".equals(t.get("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("orders table not found"));

        var columnNames = StreamSupport.stream(ordersTable.get("columns").spliterator(), false)
                .map(c -> c.get("name").asText())
                .toList();

        assertThat(columnNames).contains("id", "customer_id", "status", "total");
    }

    @Test
    void columnsHaveForeignKeyMetadata() throws Exception {
        McpSchema.CallToolResult result = new InspectSchema(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "orders"));

        assertThat(result.isError()).isFalse();

        JsonNode tables = json(result);
        JsonNode ordersTable = StreamSupport.stream(tables.spliterator(), false)
                .filter(t -> "orders".equals(t.get("name").asText()))
                .findFirst()
                .orElseThrow();

        // customer_id column must have foreignKeyTable pointing to customers
        boolean hasFkColumn = StreamSupport.stream(ordersTable.get("columns").spliterator(), false)
                .anyMatch(c -> "customer_id".equals(c.get("name").asText())
                        && c.has("foreignKeyTable")
                        && "customers".equals(c.get("foreignKeyTable").asText()));

        assertThat(hasFkColumn).as("customer_id must have foreignKeyTable=customers").isTrue();
    }
}
