package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class FindImpactTest extends BaseIntegrationTest {

    @Test
    void customersTableHasOrdersAsFkDependent() throws Exception {
        // orders has a FK pointing to customers — dropping customers would break orders
        McpSchema.CallToolResult result = new FindImpact(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "customers"));

        assertThat(result.isError()).isFalse();

        JsonNode fkDependents = json(result).get("impact").get("fk_dependents");
        assertThat(fkDependents.isArray()).isTrue();
        assertThat(fkDependents.size()).isGreaterThan(0);

        var dependentTables = StreamSupport.stream(fkDependents.spliterator(), false)
                .map(d -> d.get("dependent_table").asText())
                .toList();

        assertThat(dependentTables).contains("orders");
    }

    @Test
    void ordersTableHasTrigger() throws Exception {
        McpSchema.CallToolResult result = new FindImpact(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "orders"));

        assertThat(result.isError()).isFalse();

        JsonNode triggers = json(result).get("impact").get("triggers");
        assertThat(triggers.isArray()).isTrue();
        assertThat(triggers.size()).isGreaterThan(0);

        var triggerNames = StreamSupport.stream(triggers.spliterator(), false)
                .map(t -> t.get("name").asText())
                .toList();

        assertThat(triggerNames).contains("trg_orders_before_update");
    }

    @Test
    void ordersTableHasDependentProcedureAndView() throws Exception {
        McpSchema.CallToolResult result = new FindImpact(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "orders"));

        assertThat(result.isError()).isFalse();

        JsonNode impact = json(result).get("impact");

        // sp_cancel_order references orders → should appear in procedures
        JsonNode procedures = impact.get("procedures");
        var procNames = StreamSupport.stream(procedures.spliterator(), false)
                .map(p -> p.get("name").asText())
                .toList();
        assertThat(procNames).contains("sp_cancel_order");

        // v_customer_orders references orders → should appear in views
        JsonNode views = impact.get("views");
        var viewNames = StreamSupport.stream(views.spliterator(), false)
                .map(v -> v.get("name").asText())
                .toList();
        assertThat(viewNames).contains("v_customer_orders");
    }

    @Test
    void summaryCountsMatchArraySizes() throws Exception {
        McpSchema.CallToolResult result = new FindImpact(jdbcClient)
                .apply(null, req("schema", "test_schema", "table", "orders"));

        assertThat(result.isError()).isFalse();

        JsonNode root = json(result);
        JsonNode impact  = root.get("impact");
        JsonNode summary = root.get("summary");

        assertThat(summary.get("fk_dependent_count").asInt())
                .isEqualTo(impact.get("fk_dependents").size());
        assertThat(summary.get("trigger_count").asInt())
                .isEqualTo(impact.get("triggers").size());
        assertThat(summary.get("procedure_count").asInt())
                .isEqualTo(impact.get("procedures").size());
        assertThat(summary.get("view_count").asInt())
                .isEqualTo(impact.get("views").size());
    }

    @Test
    void missingTableParameterReturnsError() {
        McpSchema.CallToolResult result = new FindImpact(jdbcClient)
                .apply(null, req("schema", "test_schema")); // no "table"

        assertThat(result.isError()).isTrue();
    }
}
