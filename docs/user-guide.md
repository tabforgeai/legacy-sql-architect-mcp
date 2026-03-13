# Legacy SQL Architect MCP — User Guide

## Table of Contents

1. [What Makes This Tool Different](#1-what-makes-this-tool-different)
2. [Available Tools — Overview](#2-available-tools--overview)
3. [Tool Reference with Prompt Examples](#3-tool-reference-with-prompt-examples)
   - [inspect_schema](#31-inspect_schema)
   - [data_sampler](#32-data_sampler)
   - [get_procedure_source](#33-get_procedure_source)
   - [query_plan_expert](#34-query_plan_expert)
   - [dependency_graph](#35-dependency_graph)
   - [generate_mermaid_erd](#36-generate_mermaid_erd)
   - [generate_documentation](#37-generate_documentation)
   - [find_impact](#38-find_impact)
   - [generate_java_dao](#39-generate_java_dao)
4. [Multi-Tool Workflows](#4-multi-tool-workflows)
5. [Tips for Better Results](#5-tips-for-better-results)

---

## 1. What Makes This Tool Different

Most database tools show you *data*. Legacy SQL Architect MCP shows the AI *everything it needs to understand your database* — structure, logic, relationships, and real data — so it can reason about your system the way a senior DBA would.

### Key differentiators

**Works with legacy databases, not just modern ones.**
Designed from the ground up for databases that have been running for years or decades — full stored procedure source retrieval, trigger dependency mapping, and impact analysis work on PostgreSQL, SQL Server, and Oracle, including large PL/SQL packages that other tools fail on (ORA-01489 is handled gracefully).

**Read-only by design.**
The server connects in strict read-only mode. It physically cannot modify your database — no INSERT, no UPDATE, no DELETE. Safe to use on production databases.

**No framework lock-in.**
Pure Java + JDBC. No Spring, no ORM, no Docker required. Runs as a lightweight native installer on Windows, macOS, and Linux. No infrastructure to maintain.

**Combines structure + logic + real data in a single conversation.**
The AI can look at the schema, then read the stored procedure that processes orders, then sample a few real orders to understand why something is broken — all in one conversation, without you having to copy-paste anything.

**Generates working Java code, not pseudocode.**
`generate_java_dao` produces ready-to-compile Entity + Repository classes (plain JDBC, Java 17+) directly from your live schema — no mapping files, no guessing column types.

**The AI understands relationships, not just tables.**
`dependency_graph` and `find_impact` trace how tables, triggers, procedures, views, and foreign keys are connected — so the AI can tell you *what breaks* before you change anything.

---

## 2. Available Tools — Overview

| Tool | What it does |
|------|-------------|
| `inspect_schema` | Lists tables with columns, types, primary keys, and foreign keys |
| `data_sampler` | Returns sample rows from a table (sensitive columns masked) |
| `get_procedure_source` | Retrieves full source code of stored procedures and functions |
| `query_plan_expert` | Explains the execution plan for a SQL query, flags full table scans |
| `dependency_graph` | Maps all dependencies: FK chains, trigger→procedure call chains, view dependencies |
| `generate_mermaid_erd` | Generates an Entity-Relationship Diagram in Mermaid format |
| `generate_documentation` | Generates Markdown documentation for the entire schema |
| `find_impact` | Shows what triggers, procedures, and views reference a given table |
| `generate_java_dao` | Generates Java Entity + Repository classes (plain JDBC) for each table |

---

## 3. Tool Reference with Prompt Examples

### 3.1 inspect_schema

**What it does:** Returns the full structure of your database schema — all tables with their columns, data types, nullability, primary keys, and foreign key relationships.

**When to use it:** Start here. Always inspect the schema first so the AI understands the data model before you ask it anything else.

**Parameters:**
- `schema` — schema name (optional, defaults to config.json)
- `table` — specific table name (optional, returns only that table)

---

**Prompt examples:**

> "Inspect the schema and give me an overview of the data model."

> "Inspect the schema and tell me which tables have the most foreign key relationships."

> "Inspect the `orders` table and explain what each column is for."

> "Inspect the schema. Which tables look like they store audit or history data?"

> "Inspect the schema, then tell me — is this database normalized? What patterns do you see?"

---

### 3.2 data_sampler

**What it does:** Returns a sample of real rows from a table. Columns whose names suggest sensitive data (password, token, secret, ssn, credit_card, etc.) are automatically masked.

**When to use it:** After inspecting the schema, sample data helps the AI understand what the actual data looks like — column formats, value patterns, what "status" values are actually used, etc.

**Parameters:**
- `schema` — schema name (optional)
- `table` — table name (required)
- `limit` — number of rows (optional, default from config.json, max 100)

---

**Prompt examples:**

> "Sample 5 rows from the `orders` table and explain what the `status` column values mean."

> "Sample the `customers` table. What does the data look like? Are there any data quality issues?"

> "Sample the `order_items` table and tell me how prices are stored — are they integers, decimals, with or without tax?"

> "Sample the `audit_log` table. What events are being tracked?"

> "Sample both `orders` and `order_items`, then explain how an order is structured."

---

### 3.3 get_procedure_source

**What it does:** Retrieves the complete source code of stored procedures and functions directly from the database catalog. Returns the full CREATE OR REPLACE statement — not a summary, the actual code.

**When to use it:** When you need to understand business logic that lives inside the database. Especially powerful for legacy systems where critical rules are in stored procedures rather than application code.

**Parameters:**
- `schema` — schema name (optional)
- `procedure_name` — specific procedure/function name (optional; omit to get all)

---

**Prompt examples:**

> "Get the source code of the `sp_process_order` procedure and explain what business rules it enforces."

> "Retrieve all stored procedures in the schema and give me a summary of what each one does."

> "Get the source of `fn_calculate_discount` and tell me under what conditions a discount is applied."

> "Read the `sp_close_invoice` procedure. Is there anything that could cause it to fail silently?"

> "Retrieve `sp_cancel_order`. What happens to inventory when an order is cancelled? Does it roll back stock?"

> "Get all procedures. Which ones modify the `orders` table?"

---

### 3.4 query_plan_expert

**What it does:** Runs EXPLAIN (ANALYZE) on a SQL query and interprets the execution plan. Automatically flags full table scans on large tables and suggests missing indexes.

**When to use it:** When a query is slow and you want to know why, or when you want to validate that a new query will use indexes efficiently.

**Parameters:**
- `schema` — schema name (optional)
- `sql` — the SQL query to analyze (required)

---

**Prompt examples:**

> "Analyze this query: `SELECT * FROM orders WHERE customer_id = 123 AND status = 'PENDING'` — is it using indexes?"

> "This query is slow in production: `SELECT o.*, c.name FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.created_at > '2024-01-01'`. What does the query plan say?"

> "Analyze `SELECT COUNT(*) FROM order_items WHERE product_id = 42`. Would adding an index on `product_id` help?"

> "Check the query plan for this report query and tell me if it's likely to cause performance problems: [paste SQL]"

---

### 3.5 dependency_graph

**What it does:** Builds a complete map of how objects in your schema depend on each other — foreign key chains between tables, which triggers call which procedures, which views join which tables. Returns a graph of edges you can traverse or visualize.

**When to use it:** Before making structural changes. Before you rename a column or drop a table, find out everything that depends on it.

**Parameters:**
- `schema` — schema name (optional)

---

**Prompt examples:**

> "Build the dependency graph for this schema and show me all the relationships."

> "Show me the dependency graph. Which table is the most central — referenced by the most other tables?"

> "Build the dependency graph and trace the chain: when a row is inserted into `orders`, what triggers fire, and what procedures do they call?"

> "I want to drop the `legacy_pricing` table. Use the dependency graph to tell me everything that would break."

> "Build the dependency graph and find any circular dependencies."

> "Show me the dependency graph as a list of edges, then describe the overall architecture of this database."

---

### 3.6 generate_mermaid_erd

**What it does:** Generates an Entity-Relationship Diagram (ERD) in Mermaid format. The diagram includes all tables, their columns with types, and foreign key relationships with proper ERD notation (`||--o{`).

**When to use it:** When you need a visual representation of the schema — for documentation, onboarding new developers, or presenting to stakeholders.

**Parameters:**
- `schema` — schema name (optional)

---

**Prompt examples:**

> "Generate a Mermaid ERD for this schema."

> "Generate the ERD and then explain the diagram to me — what does this database model represent as a business domain?"

> "Create an ERD and embed it in a Markdown document I can share with my team."

*(The output is a fenced Mermaid code block. Paste it into any Mermaid-compatible renderer: GitHub, GitLab, Notion, Confluence, mermaid.live, etc.)*

---

### 3.7 generate_documentation

**What it does:** Generates complete Markdown documentation for the entire schema — tables, columns, data types, keys, foreign key relationships, and all stored procedures with their source code. Everything in one document.

**When to use it:** When you need to create or refresh technical documentation. Especially useful when inheriting a legacy system with no existing docs.

**Parameters:**
- `schema` — schema name (optional)

---

**Prompt examples:**

> "Generate full Markdown documentation for this schema."

> "Generate the schema documentation and save it as `DATABASE.md` in my project."

> "Generate documentation, then review it and add a business-level summary at the top explaining what this database is for."

> "Generate documentation for the `billing` schema only."

---

### 3.8 find_impact

**What it does:** Given a table name, finds all database objects that reference or depend on it: triggers defined on the table, stored procedures and functions that query it, and views that join it. Answers the question "what will break if I change this table?"

**When to use it:** Before any schema migration, column rename, or table modification. Essential for impact analysis on legacy systems.

**Parameters:**
- `schema` — schema name (optional)
- `table` — the table to analyze (required)

---

**Prompt examples:**

> "Find the impact of changing the `orders` table — what procedures, triggers, and views depend on it?"

> "I need to add a NOT NULL column to `customers`. What would be impacted?"

> "Find impact for the `products` table. Is it safe to rename the `unit_price` column?"

> "Show me everything that depends on the `accounts` table. I want to know the full blast radius before we touch it."

> "Find the impact of the `legacy_codes` table. Is it still actively used anywhere, or is it safe to archive?"

---

### 3.9 generate_java_dao

**What it does:** Generates plain Java source code for each table — an Entity POJO and a Repository class with full CRUD operations (findById, findAll, insert, update, deleteById). No Spring, no JPA, no annotations — pure `java.sql.*`. Ready to compile and use.

**When to use it:** When you need to bootstrap a Java data access layer for a legacy database that previously had no Java code. Or when you want a clean starting point for a migration project.

**Parameters:**
- `schema` — schema name (optional)
- `table_filter` — SQL LIKE pattern to select specific tables (e.g. `ORD%`, optional)
- `package_name` — Java package name for generated classes (optional, default: `com.example.dao`)

---

**Prompt examples:**

> "Generate Java DAO classes for all tables in the schema, using package `com.acme.repository`."

> "Generate a Java Entity and Repository for the `orders` table."

> "Generate DAO classes for all tables matching `INV%` — these are the invoice tables."

> "Generate Java DAO classes, then write the generated source files into `src/main/java/com/acme/dao/`."

> "Generate DAO classes for the `customers` table, then modify the Repository to add a `findByEmail(String email)` method."

---

## 4. Multi-Tool Workflows

The real power of Legacy SQL Architect MCP comes from combining tools in a single conversation. The AI builds up context across tool calls.

---

### Workflow 1: "I inherited this database — what is it?"

```
1. inspect_schema          → understand the structure
2. generate_mermaid_erd    → visualize relationships
3. data_sampler            → see what real data looks like
4. get_procedure_source    → read the business logic in stored procedures
5. generate_documentation  → produce handover documentation
```

**Single prompt to start:**
> "I've just inherited this database and have no documentation. Start by inspecting the schema, then generate an ERD, sample a few key tables, read all stored procedures, and finally produce a complete Markdown documentation file. Give me a summary of what this database is for."

---

### Workflow 2: "Why is this slow?"

```
1. inspect_schema          → check if there are indexes on relevant columns
2. query_plan_expert       → analyze the slow query
3. data_sampler            → check data volume and value distribution
```

**Prompts:**
> "Inspect the schema, paying attention to indexes on the `orders` table."
> "Now analyze this query with query_plan_expert: `SELECT * FROM orders WHERE status = 'PENDING' AND created_at > '2024-01-01'`"
> "Sample the `orders` table and tell me how many rows there might be based on the data."

---

### Workflow 3: "Is it safe to change this table?"

```
1. find_impact             → discover all dependencies
2. get_procedure_source    → read the procedures that use this table
3. dependency_graph        → see the full chain of effects
```

**Single prompt:**
> "I need to rename the `unit_price` column in `order_items` to `price_excl_tax`. Use find_impact, get_procedure_source, and the dependency graph to tell me exactly what I need to change and what could break."

---

### Workflow 4: "Build a Java service for this database"

```
1. inspect_schema          → understand the model
2. generate_java_dao       → generate Entity + Repository classes
3. get_procedure_source    → understand complex logic to add custom methods
```

**Prompts:**
> "Inspect the schema to understand the data model."
> "Generate Java DAO classes for all tables using package `com.example.service.repository`."
> "Now read the `sp_process_payment` procedure and add a custom `processPayment()` method to the appropriate repository."

---

### Workflow 5: "Explain this system to a new developer"

```
1. inspect_schema
2. generate_mermaid_erd
3. get_procedure_source
4. generate_documentation
```

**Single prompt:**
> "Prepare a technical onboarding document for a new developer joining our team. Inspect the schema, generate an ERD, read all stored procedures, and write a comprehensive Markdown document that explains the data model, the business rules encoded in the database, and any gotchas a developer should know about."

---

## 5. Tips for Better Results

**Always start with inspect_schema.**
The AI builds its understanding of your database from the schema. If you ask it about orders before it has seen the schema, it has to guess. Inspect first, then ask.

**Be specific about the schema name if you have multiple schemas.**
Add `in schema 'billing'` or `using schema 'dbo'` to your prompt if you have more than one schema and the wrong one might be selected.

**Chain tool calls in one prompt.**
You don't have to call tools one at a time. "Inspect the schema, then sample the orders table, then tell me..." — the AI will call tools in sequence and synthesize the results.

**Use find_impact before any schema change.**
Even if you are confident about a change, run find_impact first. Legacy databases frequently have hidden dependencies — a view nobody remembers, a trigger added years ago — that will break silently.

**Ask the AI to save generated code to files.**
"Generate Java DAO classes and write each file to `src/main/java/com/acme/dao/`" — the AI agent (Claude Desktop with filesystem access) can write the files directly.

**Sample data before drawing conclusions.**
Schema alone can mislead. A column called `status` might have 15 possible values in theory but only 3 in practice. Sample the data to understand what is actually happening in production.

**Use table_filter for large schemas.**
If your schema has hundreds of tables, use `table_filter` with a SQL LIKE pattern to focus on the relevant subset: `"table_filter": "ORD%"` for all order-related tables.
