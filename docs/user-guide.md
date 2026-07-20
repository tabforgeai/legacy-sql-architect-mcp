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
   - [inspect_apex_performance](#310-inspect_apex_performance)
   - [get_apex_source](#311-get_apex_source)
   - [inspect_apex_debug](#312-inspect_apex_debug)
   - [apex_config_audit](#313-apex_config_audit)
   - [apex_sql_runtime_stats](#314-apex_sql_runtime_stats)
   - [apex_explain_batch](#315-apex_explain_batch)
4. [The APEX Performance Toolkit — How the Six Tools Fit Together](#4-the-apex-performance-toolkit)
5. [Enabling APEX Debug (for inspect_apex_debug)](#5-enabling-apex-debug)
6. [Multi-Tool Workflows](#6-multi-tool-workflows)
7. [Tips for Better Results](#7-tips-for-better-results)

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
| `inspect_apex_performance` | Analyzes an Oracle APEX application — slowest pages from activity log, SQL from page regions, LOVs, validations, processes, plus recommendations *(Oracle only)* |
| `get_apex_source` | Extracts the embedded source code of APEX components (regions, processes, validations, LOVs, dynamic actions, …) with a code-wide search *(Oracle only)* |
| `inspect_apex_debug` | Reads APEX's own debug trace to show, step by step, where a captured page render actually spent its time *(Oracle only)* |
| `apex_config_audit` | Static audit of APEX component settings for performance anti-patterns (row-count pagination, unbounded max-rows, uncached LOVs, too many server-side dynamic actions) *(Oracle only)* |
| `apex_sql_runtime_stats` | Correlates an app's SQL with its real runtime cost from V$SQL (buffer gets, executions, elapsed/exec, plan hash) *(Oracle only)* |
| `apex_explain_batch` | Runs EXPLAIN PLAN over every embedded region/LOV/validation query and flags full scans and Cartesian joins *(Oracle only)* |

The last six tools form a dedicated **Oracle APEX performance toolkit** — see [section 4](#4-the-apex-performance-toolkit) for how they chain together.

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

### 3.10 inspect_apex_performance

**What it does:** Analyzes an Oracle APEX application for performance issues. Queries APEX dictionary views to find the slowest pages (average and maximum elapsed time, call count) and retrieves the SQL queries embedded in Interactive Reports, Classic Reports, and Interactive Grids. Missing privileges on individual APEX views are reported as warnings rather than hard failures — partial results are still returned.

**When to use it:** When an Oracle APEX application is slow and you need to find out which pages are the problem and which SQL queries inside those pages are causing it. This tool gives you the raw data; combine it with `query_plan_expert` to get the actual diagnosis.

**Oracle only.** APEX runs exclusively on Oracle. The tool returns an error immediately if the configured database is not Oracle.

**Parameters:**
- `app_id` — APEX application ID (optional; omit to list all applications in the workspace)
- `page_id` — filter to a specific page (optional; requires `app_id`)
- `top_n` — number of slowest pages to return from the activity log (optional, default: 10)
- `include_sql` — whether to include SQL source from page regions (optional, default: true)
- `days_back` — how many days of activity log history to analyze (optional, default: 30)
- `include_lovs` — include shared List of Values SQL analysis (optional, default: true)
- `include_validations` — include page items with SQL sources and SQL-type validations (optional, default: true)
- `include_processes` — include page processes (PL/SQL blocks, DML processes) and their execution points (optional, default: true)
- `include_recommendations` — include auto-generated performance recommendations (optional, default: true)

**Required Oracle privileges** (the APEX_* views are normally readable by any user via public synonyms; grant explicitly only if your environment restricts them):
```sql
GRANT SELECT ON APEX_APPLICATIONS                TO your_user;
GRANT SELECT ON APEX_APPLICATION_PAGES           TO your_user;
GRANT SELECT ON APEX_APPLICATION_PAGE_REGIONS    TO your_user;
GRANT SELECT ON APEX_WORKSPACE_ACTIVITY_LOG      TO your_user;  -- per-page-view timing
GRANT SELECT ON APEX_APPLICATION_LOVS            TO your_user;
GRANT SELECT ON APEX_APPLICATION_PAGE_ITEMS      TO your_user;
GRANT SELECT ON APEX_APPLICATION_PAGE_VAL        TO your_user;  -- validations
GRANT SELECT ON APEX_APPLICATION_PAGE_PROC       TO your_user;
GRANT SELECT ON APEX_APPLICATION_PROCESSES       TO your_user;
GRANT SELECT ON APEX_APPLICATION_COMPUTATIONS    TO your_user;
```
> These view/column names are verified against Oracle APEX 26.1. The tool degrades
> gracefully — any view it cannot read becomes a `privilege_warnings` entry, and the
> remaining sections still return.

---

**Prompt examples:**

> "List all APEX applications in this workspace."

> "Inspect APEX performance for application 100. Which pages are the slowest?"

> "Analyze APEX app 100 over the last 60 days — show me the top 20 slowest pages."

> "Look at APEX application 100, page 12. Show me all the SQL queries in the regions on that page."

> "My APEX app (id=100) is slow. Inspect its performance and tell me which page is the worst offender."

> "Inspect APEX performance for app 100 — I don't need the SQL source, just the slowest pages. Set include_sql to false."

> "Analyze APEX app 100 performance. Then take the slowest page's region SQL and run it through query_plan_expert."

> "Check APEX app 100 — are there any warnings about missing privileges? Which views couldn't be accessed?"

> "Analyze APEX app 100 — I only want LOV analysis and recommendations, skip region SQL. Set include_sql to false."

> "What are the auto-generated recommendations for APEX app 100? Focus on HIGH priority ones."

> "Inspect APEX app 100. The LOV 'Employee Names' is listed as LOV_NO_WHERE — what does that mean and how do I fix it?"

---

**What the recommendations engine detects:**

| Category | Priority | Trigger |
|----------|----------|---------|
| `SLOW_PAGE` | HIGH / MEDIUM | Page with avg response > 3000ms / 1000ms |
| `LOV_NO_WHERE` | HIGH / MEDIUM | SQL LOV without a WHERE clause (full table scan on every render) |
| `LOV_HIGH_USAGE` | MEDIUM | SQL LOV referenced by 3+ page items (wide blast radius) |
| `LOV_SELECT_STAR` | LOW | LOV using `SELECT *` (LOVs need only 2 columns) |
| `REGION_SELECT_STAR` | LOW | Report region using `SELECT *` |
| `VALIDATION_SQL` | LOW | SQL validation (Exists / NOT Exists / PL-SQL) firing on every form submit |
| `ITEM_SOURCE_SQL` | LOW | Item whose value comes from a SQL query, firing on every page load |
| `PROCESS_UNCONDITIONAL` | HIGH / MEDIUM / LOW | Page-level PL/SQL/DML process with no condition — always fires on load or submit |
| `APP_PROCESS_UNCONDITIONAL` | **Always HIGH** | Application-level process with no condition — runs on every page for every user |
| `APP_COMPUTATION_SQL` | MEDIUM | Application-level SQL computation with no condition — database query on every page load |

---

**Recommended workflow for diagnosing a slow APEX page:**

```
Step 1 — inspect_apex_performance (app_id only)
         → identifies the slowest pages by avg_elapsed_ms

Step 2 — inspect_apex_performance (app_id + page_id of the slowest page)
         → returns SQL queries from all regions on that page

Step 3 — query_plan_expert (each region's sql_query)
         → EXPLAIN PLAN, flags TABLE ACCESS FULL, suggests indexes

Step 4 — inspect_schema (tables from the slow query)
         → confirms which indexes exist and which are missing
```

**Single prompt that runs the full workflow:**
> "My Oracle APEX application (id=100) is slow. First inspect its performance to find the slowest page. Then look at the SQL regions on that page. Then run the most suspicious query through query_plan_expert and tell me what indexes to create."

---

### 3.11 get_apex_source

**What it does:** Extracts the actual embedded source code of APEX application components — the SQL and PL/SQL that lives *inside* the app, not in the database catalog. Covers 12 component types: regions, page processes, computations, validations, items, branches, dynamic actions, dynamic action actions, LOVs, authorization schemes, application processes, and application computations. Supports a code-wide `search` (grep across every component's code) and truncation of long snippets.

**When to use it:** When you need to read what a component actually *does* — for example, after `inspect_apex_debug` or `apex_explain_batch` points at a specific region/process, use this to pull its code. Also great for "find every place this table/function/hint is used across the whole app."

**How it stays version-proof:** the tool introspects each dictionary view's columns (via `ALL_TAB_COLUMNS`) and selects only the columns that exist on your APEX version, so it tolerates dictionary drift across APEX 19.x–26.x without ORA-00904 errors.

**Oracle only.**

**Parameters:**
- `app_id` — APEX application ID (optional; omit to list applications)
- `page_id` — restrict to a specific page (optional)
- `component_types` — array subset of the 12 types to extract (optional; default all)
- `search` — case-insensitive text to grep across all component code (optional)
- `max_code_length` — truncate each code snippet to N chars (optional; 0 = unlimited)

**Prompt examples:**

> "Get the APEX source for app 100, page 12 — show me the SQL in every region and process."

> "Search all of APEX app 100 for any component that references the `EMPLOYEES` table."

> "In APEX app 100, find every LOV whose query uses `SELECT *`."

> "Show me the PL/SQL code of all page processes on page 30 of APEX app 100."

> "Search APEX app 100 for the optimizer hint `/*+ FULL */` — is it used anywhere?"

---

### 3.12 inspect_apex_debug

**What it does:** Reads APEX's own debug trace (`APEX_DEBUG_MESSAGES`) and reconstructs each captured "page view", then drills into the slowest one to show its individual trace steps ordered by execution time — the empirical "where did this render actually spend its time" breakdown. Reports each step's execution/elapsed time in milliseconds and produces `DEBUG_SLOW_STEP` recommendations pointing you to the exact page to open.

**When to use it:** When you have a slow page and want *measured* proof of which step is slow (not a static guess). Requires that APEX debug was enabled when the page ran — see [section 5](#5-enabling-apex-debug).

**Oracle only.**

**Parameters:**
- `app_id` — APEX application ID (optional; omit to list applications)
- `page_id` — restrict to a specific page (optional)
- `page_view_id` — drill into a specific captured page view (optional; default = auto-drill into the slowest)
- `top_n` — number of slowest page views / slowest steps to return (optional, default 10)
- `days_back` — debug-history lookback window in days (optional, default 7)
- `min_execution_ms` — only return steps at least this slow (optional, default 0)

**Prompt examples:**

> "Enable-debug is on and I reproduced the slow page. Inspect APEX debug for app 100 — which page view was slowest and which step inside it took the most time?"

> "Inspect APEX debug for app 100, page 12. Show me the 15 slowest steps over the last 2 days."

> "Inspect APEX debug for app 100 and only show steps slower than 200ms."

> "There's no debug data for app 100 — what do I need to do to capture it?"

---

### 3.13 apex_config_audit

**What it does:** A **static** audit of an app's component *settings* (no SQL parsing, no runtime data) for well-known performance anti-patterns. Detects: row-count pagination schemes that force a `COUNT(*)` over the whole result set, regions with an unbounded "Maximum Rows To Query", widely-used SQL LOVs with no result caching, pages with many "Execute Server-side Code" dynamic actions, and pages crowded with synchronous SQL regions. Returns a per-category summary and prioritized findings.

**When to use it:** As a fast first pass on any app — it works even with zero activity/debug data, so it's the ideal "what's obviously mis-configured?" check before you dig into runtime metrics.

**Oracle only.**

**Parameters:**
- `app_id` — APEX application ID (optional; omit to list applications)
- `page_id` — restrict region/dynamic-action checks to one page (optional)

**Findings:**

| Category | Priority | Trigger |
|----------|----------|---------|
| `PAGINATION_ROW_COUNT` | MEDIUM | Report region with a "… of Z" pagination scheme (COUNT over full result set each render) |
| `UNBOUNDED_MAX_ROWS` | MEDIUM / LOW | Maximum Rows To Query > 10000 (MEDIUM) or unset (LOW) |
| `LOV_NO_CACHE` | LOW | SQL LOV used by 3+ items with no result caching |
| `TOO_MANY_SERVER_DAS` | MEDIUM | Page with 5+ "Execute Server-side Code" dynamic actions (one AJAX round-trip each) |
| `MANY_SQL_REGIONS_PER_PAGE` | LOW | Page with 6+ SQL regions, few lazy-loaded (all query synchronously on load) |

**Prompt examples:**

> "Run a config audit on APEX app 100. What are the biggest configuration problems?"

> "Audit the configuration of app 100, page 3100 — is anything mis-set on that page?"

> "Config-audit APEX app 100 and show me only the MEDIUM findings."

> "Which pages in app 100 have too many server-side dynamic actions?"

---

### 3.14 apex_sql_runtime_stats

**What it does:** Correlates an app's SQL with its **real runtime cost** from the Oracle shared pool (`V$SQL`). Filters `V$SQL` by the application's parsing schema and ranks statements by a chosen metric (buffer gets, elapsed, executions, CPU, disk reads), returning executions, buffer gets (total and per-exec), disk reads, rows, elapsed/CPU ms per execution, plan hash, and the SQL text. An optional `sql_like` filter pins a specific query fragment. Produces `HOT_SQL` recommendations for the expensive statements.

**When to use it:** When you want the empirical "what actually costs the most" — the truth that separates a scary-looking query from a genuinely expensive one.

**Important limitations:**
- A statement appears only while it is still **cached** in the shared pool. Run the pages of interest first, then query this tool.
- Reads only the always-available `V$SQL` — **no AWR/ASH** (those need the Oracle Diagnostics Pack license).
- Needs `SELECT` on `V$SQL`. Missing privilege degrades to a warning, not a crash.

**Oracle only.**

**Parameters:**
- `app_id` — APEX application ID (its `OWNER` schema is the V$SQL filter)
- `order_by` — `buffer_gets` (default) | `elapsed` | `executions` | `cpu` | `disk_reads`
- `top_n` — number of statements to return (optional, default 20)
- `min_executions` — ignore statements run fewer times than this (optional, default 1)
- `sql_like` — optional case-insensitive SQL_TEXT fragment to narrow the match

**Prompt examples:**

> "Show me the most expensive SQL for APEX app 100 by buffer gets."

> "Runtime SQL stats for app 100, ordered by elapsed time per execution — top 10."

> "For app 100, find the runtime cost of any statement that touches the `EMPLOYEES` table (use sql_like)."

> "Get the hottest SQL for app 100, then pass the worst sql_id to query_plan_expert."

**Recommended grant (if the app user lacks it):**
```sql
GRANT SELECT ON V_$SQL TO your_user;   -- or: GRANT SELECT_CATALOG_ROLE TO your_user;
```

---

### 3.15 apex_explain_batch

**What it does:** Runs `EXPLAIN PLAN` over **every** SQL statement embedded in an app's report/LOV/validation components and reports each statement's optimizer cost, estimated rows, and structural red flags (`TABLE ACCESS FULL`, `MERGE JOIN CARTESIAN`, `INDEX FULL SCAN`) — without executing any of them. It turns "here are 200 embedded queries" into "these 6 have a full table scan."

**How it handles APEX-isms:** bind variables (`:P1_X`) need no definition; APEX substitution strings (`&ITEM.`) are neutralized to a bind placeholder so the statement parses; a statement that still can't be parsed is reported with `status: "error"` and its Oracle message, never aborting the batch. EXPLAIN PLAN writes only to the session `PLAN_TABLE` (no business data touched); the tool transiently lifts its read-only flag for the batch and restores it afterward.

**When to use it:** As a bulk triage — one call surfaces the shortlist of queries worth handing to `query_plan_expert` and `get_apex_source`.

**Oracle only.**

**Parameters:**
- `app_id` — APEX application ID (optional; omit to list applications)
- `page_id` — restrict region/validation statements to one page (optional)
- `component_types` — subset of `["regions","lovs","validations"]` (optional; default all)
- `max_statements` — safety cap on how many statements to explain (optional, default 100)

**Prompt examples:**

> "EXPLAIN every query in APEX app 100, page 12. Which ones have a full table scan?"

> "Batch-explain all region and LOV SQL for app 100 and list only the statements with red flags."

> "Run apex_explain_batch on app 100 and give me the 5 highest-cost statements."

> "Explain the validation queries on page 30 of app 100 — any Cartesian joins?"

---

## 4. The APEX Performance Toolkit

The six Oracle-only tools above are designed to work as a set. Each answers a different question, and they hand off to one another:

| Question | Tool | Needs runtime data? |
|----------|------|--------------------|
| "What's obviously mis-configured?" | `apex_config_audit` | No — pure settings |
| "Which embedded queries have bad plans?" | `apex_explain_batch` | No — static EXPLAIN |
| "What SQL/PLSQL does this component contain?" | `get_apex_source` | No |
| "Where does the app spend time overall?" | `inspect_apex_performance` | Activity log |
| "Which step of *this* render was slow?" | `inspect_apex_debug` | Debug trace |
| "What does this SQL actually cost to run?" | `apex_sql_runtime_stats` | Shared pool (V$SQL) |

**A complete diagnosis, start to finish:**

```
1. apex_config_audit (app_id)        → fix the obvious config anti-patterns first
2. apex_explain_batch (app_id)       → shortlist queries with full scans / Cartesian joins
3. inspect_apex_performance (app_id) → find the slowest pages + the recommendations engine
4. inspect_apex_debug (app_id)       → (with debug enabled) measure the slowest render's steps
5. apex_sql_runtime_stats (app_id)   → confirm the real runtime cost of the suspect SQL
6. get_apex_source (app_id, page_id) → read the offending component's code to fix it
   query_plan_expert (that SQL)      → get the execution plan + index advice
```

**Single prompt for the whole toolkit:**
> "Do a full performance diagnosis of Oracle APEX app 100. Start with a config audit, then batch-explain the embedded SQL to find bad plans, then find the slowest pages, then check the real runtime cost of the worst queries in V$SQL, and finally read the source of the top offender and tell me exactly what to change — prioritized."

---

## 5. Enabling APEX Debug

`inspect_apex_debug` reads data that APEX only writes **when debug is enabled** for the session/request. On a fresh app the debug tables are empty, and the tool will tell you so. To capture a trace:

**1. Allow debugging in the application**
- In App Builder: **Edit Application Definition → Properties → Debugging = "Yes"** (Debugging must not be "No").

**2. Run the slow page with debug on** (any one of):
- Append `&p_debug=YES` (or the level `&p_debug=LEVEL9`) to the page URL, **or**
- On the Developer Toolbar at the bottom of a running page, click **Debug**, then reload the page, **or**
- Set it programmatically for a session with `apex_debug`.

**3. Reproduce the slowness** — click through the page exactly as a user would, so APEX records the timed trace.

**4. Query the trace**
> "Inspect APEX debug for app 100 — I just reproduced the slow page with debug on."

**Notes:**
- Debug data is retained for a limited time (configurable; often up to ~2 weeks) and then purged, so query it reasonably soon after reproducing.
- The times are stored in seconds and reported by the tool in milliseconds.
- Enabling debug adds overhead to those specific requests — turn app-level debugging back down when you're done capturing.

The same "run the pages first" principle helps `apex_sql_runtime_stats`: statements are only visible in `V$SQL` while cached, so exercise the pages, then query the stats.

---

## 6. Multi-Tool Workflows

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

### Workflow 6: "Why is my Oracle APEX application slow?" *(Oracle only)*

```
1. inspect_apex_performance  → find the slowest pages in the activity log
2. inspect_apex_performance  → drill into the slowest page, get region SQL
3. query_plan_expert         → analyze the slow region SQL with EXPLAIN PLAN
4. inspect_schema            → verify which indexes exist on the involved tables
```

**Prompts:**

> "Inspect APEX performance for app 100. Show me the 10 slowest pages over the last 30 days."

> "Page 12 is the slowest. Now inspect APEX app 100, page 12 — show me all the SQL in its regions."

> "Take the SQL from the 'Employee Search' Interactive Report region and analyze it with query_plan_expert."

> "Inspect the schema for the tables in that query. Do the filter columns have indexes?"

**Single prompt for the full diagnosis:**
> "My Oracle APEX application (id=100) has serious performance problems. Inspect its performance to find which page is slowest, get the SQL queries from the regions on that page, analyze the most expensive-looking query with query_plan_expert, and inspect the schema for the relevant tables. Give me a prioritized list of what to fix."

---

## 7. Tips for Better Results

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

**For APEX, start with the tools that need no runtime data.**
`apex_config_audit` and `apex_explain_batch` work on a fresh app with zero activity — run them first. `inspect_apex_debug` and `apex_sql_runtime_stats` need you to actually exercise the pages first (with debug enabled for the former); see [section 5](#5-enabling-apex-debug). A natural order is: config audit → explain batch → performance → (reproduce pages) → debug + runtime stats → get_apex_source to read and fix the offender.
