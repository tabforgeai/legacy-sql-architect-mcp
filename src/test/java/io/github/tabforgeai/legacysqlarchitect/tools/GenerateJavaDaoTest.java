package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateJavaDaoTest extends BaseIntegrationTest {

    // generate_java_dao returns a JSON array directly (not wrapped in an object).
    // Each element: { table, entity_class_name, repository_class_name,
    //                 entity_source, repository_source }
    // Use table_filter (SQL LIKE pattern) to narrow to a single table.

    @Test
    void returnsEntryForEachTable() throws Exception {
        McpSchema.CallToolResult result = new GenerateJavaDao(jdbcClient)
                .apply(null, req(
                        "schema",       "test_schema",
                        "package_name", "com.example.dao"
                ));

        assertThat(result.isError()).isFalse();

        JsonNode items = json(result); // top-level is the array
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isGreaterThanOrEqualTo(3); // one per table

        var tableNames = StreamSupport.stream(items.spliterator(), false)
                .map(i -> i.get("table").asText())
                .toList();

        assertThat(tableNames).contains("customers", "orders", "order_items");
    }

    @Test
    void entitySourceContainsJavaSyntax() throws Exception {
        McpSchema.CallToolResult result = new GenerateJavaDao(jdbcClient)
                .apply(null, req(
                        "schema",       "test_schema",
                        "package_name", "com.example.dao",
                        "table_filter", "orders"
                ));

        assertThat(result.isError()).isFalse();

        JsonNode items = json(result);
        assertThat(items.size()).isEqualTo(1);

        String entitySource = items.get(0).get("entity_source").asText();
        assertThat(entitySource).contains("public class");
        assertThat(entitySource).contains("com.example.dao");
    }

    @Test
    void entityClassNameIsPascalCase() throws Exception {
        McpSchema.CallToolResult result = new GenerateJavaDao(jdbcClient)
                .apply(null, req(
                        "schema",       "test_schema",
                        "package_name", "com.example.dao",
                        "table_filter", "order_items"
                ));

        assertThat(result.isError()).isFalse();

        JsonNode items = json(result);
        String entityClassName = items.get(0).get("entity_class_name").asText();

        // order_items → OrderItems (PascalCase)
        assertThat(entityClassName).isEqualTo("OrderItems");
    }

    @Test
    void repositorySourceContainsSqlQueries() throws Exception {
        McpSchema.CallToolResult result = new GenerateJavaDao(jdbcClient)
                .apply(null, req(
                        "schema",       "test_schema",
                        "package_name", "com.example.dao",
                        "table_filter", "orders"
                ));

        assertThat(result.isError()).isFalse();

        String repoSource = json(result).get(0).get("repository_source").asText();
        assertThat(repoSource).containsIgnoringCase("SELECT");
        assertThat(repoSource).containsIgnoringCase("orders");
    }
}
