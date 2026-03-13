package io.github.tabforgeai.legacysqlarchitect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tabforgeai.legacysqlarchitect.BaseIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class GetProcedureSourceTest extends BaseIntegrationTest {

    // get_procedure_source returns a JSON array directly (not wrapped in an object).
    // Each element: { name, schema, type, language, source_code }

    @Test
    void returnsAllProceduresAndFunctions() throws Exception {
        McpSchema.CallToolResult result = new GetProcedureSource(jdbcClient)
                .apply(null, req("schema", "test_schema"));

        assertThat(result.isError()).isFalse();

        JsonNode procedures = json(result); // top-level is the array
        assertThat(procedures.isArray()).isTrue();
        assertThat(procedures.size()).isGreaterThanOrEqualTo(2); // fn_ + sp_

        var names = StreamSupport.stream(procedures.spliterator(), false)
                .map(p -> p.get("name").asText())
                .toList();

        assertThat(names).contains("fn_stamp_order_updated", "sp_cancel_order");
    }

    @Test
    void sourceCodeIsNotBlank() throws Exception {
        McpSchema.CallToolResult result = new GetProcedureSource(jdbcClient)
                .apply(null, req("schema", "test_schema", "procedure_name", "sp_cancel_order"));

        assertThat(result.isError()).isFalse();

        JsonNode procedures = json(result);
        assertThat(procedures.size()).isEqualTo(1);

        String sourceCode = procedures.get(0).get("sourceCode").asText();
        assertThat(sourceCode).isNotBlank();
        assertThat(sourceCode.toLowerCase()).contains("orders");
    }

    @Test
    void languageIsPlpgsql() throws Exception {
        McpSchema.CallToolResult result = new GetProcedureSource(jdbcClient)
                .apply(null, req("schema", "test_schema", "procedure_name", "fn_stamp_order_updated"));

        assertThat(result.isError()).isFalse();

        JsonNode procedures = json(result);
        assertThat(procedures.size()).isGreaterThan(0);

        String language = procedures.get(0).get("language").asText();
        assertThat(language).containsIgnoringCase("plpgsql");
    }
}
