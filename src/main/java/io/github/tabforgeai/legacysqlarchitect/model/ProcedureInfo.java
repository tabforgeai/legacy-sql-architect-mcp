package io.github.tabforgeai.legacysqlarchitect.model;

/**
 * Represents a stored procedure or function retrieved from the database.
 *
 * Used by the {@code get_procedure_source} tool. The MCP server retrieves
 * the raw SQL source code using database-specific system catalogs and passes
 * it to the AI agent unchanged. The agent is responsible for interpreting
 * the business logic — the server intentionally does not attempt to parse
 * or summarize the procedure content.
 *
 * <p>Source retrieval is database-specific:
 * <ul>
 *   <li>PostgreSQL: {@code pg_get_functiondef(p.oid)} via {@code pg_proc} /
 *       {@code pg_namespace} — returns the complete {@code CREATE OR REPLACE FUNCTION}
 *       statement, which is more complete than {@code information_schema.routine_definition}
 *       (the latter can be truncated for large procedures)</li>
 *   <li>SQL Server: {@code sys.sql_modules.definition} joined with {@code sys.objects};
 *       encrypted procedures return the placeholder text
 *       {@code "(encrypted - source not available)"} in {@code sourceCode}</li>
 * </ul>
 *
 * <p>Example JSON representation of a single ProcedureInfo in the get_procedure_source output:
 * <pre>
 * {
 *   "name": "calculate_discount",
 *   "schema": "public",
 *   "type": "FUNCTION",
 *   "language": "plpgsql",
 *   "sourceCode": "CREATE OR REPLACE FUNCTION calculate_discount(customer_tier text)\n RETURNS numeric\n LANGUAGE plpgsql\nAS $function$\nBEGIN\n  IF customer_tier = 'VIP' THEN\n    RETURN 0.15;\n  ELSE\n    RETURN 0.05;\n  END IF;\nEND;\n$function$\n"
 * }
 * </pre>
 *
 * <p>Downstream consumers of this object:
 * <ul>
 *   <li>{@code get_procedure_source} tool – serializes List&lt;ProcedureInfo&gt; to JSON for the AI agent</li>
 *   <li>AI agent directly – reads {@code sourceCode} to extract embedded business rules
 *       (e.g., "VIP customers get 15% discount, others get 5%")</li>
 *   <li>AI agent + {@code data_sampler} – combined use: cross-reference procedure logic
 *       against real data to explain operational anomalies
 *       (e.g., why are orders stuck in a certain status?)</li>
 *   <li>AI agent + {@code dependency_graph} – after the dependency graph reveals which
 *       procedure a trigger calls, the agent fetches that procedure's source to understand
 *       the full execution flow end-to-end</li>
 * </ul>
 */
public class ProcedureInfo {

    /** Procedure or function name as reported by the database. */
    private String name;

    /**
     * Schema/owner that contains this procedure.
     * For PostgreSQL: schema name (e.g., "public").
     * For SQL Server: schema name (e.g., "dbo").
     * For Oracle: owner name.
     */
    private String schema;

    /**
     * Type of routine: "PROCEDURE" or "FUNCTION".
     * Value comes from information_schema.routines.ROUTINE_TYPE.
     */
    private String type;

    /**
     * The full SQL source code of this procedure.
     * For PostgreSQL: retrieved from information_schema.routines.routine_definition
     * or pg_proc.prosrc.
     * For SQL Server: retrieved from sys.sql_modules.definition.
     * For Oracle: retrieved from all_source.
     *
     * This is the raw text passed to the AI agent for analysis.
     * It may be null if the database does not expose procedure source
     * (e.g., compiled/encrypted procedures in SQL Server).
     */
    private String sourceCode;

    /**
     * Database-specific language of the procedure body.
     * Examples: "plpgsql" (PostgreSQL), "SQL", "TSQL" (SQL Server).
     * Value comes from information_schema.routines.external_language or equivalent.
     */
    private String language;

    // --- Constructors ---

    public ProcedureInfo() {
    }

    public ProcedureInfo(String name, String schema, String type) {
        this.name = name;
        this.schema = schema;
        this.type = type;
    }

    // --- Getters and Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public String toString() {
        return "ProcedureInfo{name='" + name + "', schema='" + schema + "', type='" + type + "'}";
    }
}
