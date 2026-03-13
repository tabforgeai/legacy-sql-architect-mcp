package io.github.tabforgeai.legacysqlarchitect;

/**
 * SQL statements that build the integration-test schema.
 *
 * The schema "test_schema" contains three tables, sample data, a trigger
 * function, a trigger, a view, and a stored procedure. This structure is
 * designed to exercise every dependency path used by the nine MCP tools:
 *
 *   customers ──FK── orders ──FK── order_items
 *       │
 *       └─ (FK dependent found by find_impact)
 *
 *   orders ──TRIGGER── trg_orders_before_update
 *                            │
 *                     CALLS  └── fn_stamp_order_updated()
 *
 *   v_customer_orders  (VIEW over customers + orders)
 *   sp_cancel_order()  (PROCEDURE that references orders)
 *
 * Each statement is a separate element so it can be executed one-by-one
 * via Statement.execute() — PostgreSQL JDBC does not support multi-statement
 * strings reliably outside of batch mode.
 */
public final class TestSchema {

    /** The schema name used in all integration tests. */
    public static final String SCHEMA = "test_schema";

    /** DDL + DML statements to execute in order to build the test schema. */
    public static final String[] STATEMENTS = {

        "CREATE SCHEMA test_schema",

        // ── Tables ──────────────────────────────────────────────────────────

        """
        CREATE TABLE test_schema.customers (
            id        SERIAL       PRIMARY KEY,
            full_name VARCHAR(100) NOT NULL,
            email     VARCHAR(200),
            phone     VARCHAR(20)
        )
        """,

        """
        CREATE TABLE test_schema.orders (
            id          SERIAL        PRIMARY KEY,
            customer_id INTEGER       NOT NULL REFERENCES test_schema.customers(id),
            status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
            total       NUMERIC(12,2),
            created_at  TIMESTAMP     DEFAULT NOW()
        )
        """,

        """
        CREATE TABLE test_schema.order_items (
            id           SERIAL        PRIMARY KEY,
            order_id     INTEGER       NOT NULL REFERENCES test_schema.orders(id),
            product_code VARCHAR(50)   NOT NULL,
            quantity     INTEGER       NOT NULL,
            unit_price   NUMERIC(10,2) NOT NULL
        )
        """,

        // ── Sample data ──────────────────────────────────────────────────────

        """
        INSERT INTO test_schema.customers (full_name, email, phone) VALUES
            ('Alice Smith', 'alice@example.com', '555-0100'),
            ('Bob Jones',   'bob@example.com',   '555-0200')
        """,

        """
        INSERT INTO test_schema.orders (customer_id, status, total) VALUES
            (1, 'COMPLETED', 150.00),
            (2, 'PENDING',    75.50)
        """,

        """
        INSERT INTO test_schema.order_items (order_id, product_code, quantity, unit_price) VALUES
            (1, 'PROD-001', 2, 50.00),
            (1, 'PROD-002', 1, 50.00),
            (2, 'PROD-001', 1, 75.50)
        """,

        // ── Trigger function ─────────────────────────────────────────────────

        """
        CREATE OR REPLACE FUNCTION test_schema.fn_stamp_order_updated()
        RETURNS TRIGGER LANGUAGE plpgsql AS $$
        BEGIN
            NEW.created_at := NOW();
            RETURN NEW;
        END;
        $$
        """,

        // ── Trigger on orders ────────────────────────────────────────────────

        """
        CREATE TRIGGER trg_orders_before_update
            BEFORE UPDATE ON test_schema.orders
            FOR EACH ROW
            EXECUTE FUNCTION test_schema.fn_stamp_order_updated()
        """,

        // ── View (references customers + orders) ─────────────────────────────

        """
        CREATE VIEW test_schema.v_customer_orders AS
            SELECT c.full_name AS customer_name,
                   o.id        AS order_id,
                   o.status,
                   o.total
            FROM   test_schema.customers c
            JOIN   test_schema.orders    o ON o.customer_id = c.id
        """,

        // ── Stored procedure (references orders) ─────────────────────────────

        """
        CREATE OR REPLACE PROCEDURE test_schema.sp_cancel_order(p_order_id INTEGER)
        LANGUAGE plpgsql AS $$
        BEGIN
            UPDATE test_schema.orders
            SET    status = 'CANCELLED'
            WHERE  id = p_order_id;
        END;
        $$
        """
    };

    private TestSchema() {}
}
