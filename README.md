# Legacy SQL Architect MCP

**MCP server that gives AI agents deep understanding of legacy databases.**

Connect Claude Desktop (or any MCP-compatible AI client) to your PostgreSQL, SQL Server, or Oracle database and let the AI inspect schemas, read stored procedures, analyze query performance, trace dependencies, generate documentation, and produce working Java code — all in a single conversation, without you copying a single line of SQL.

---

## Why Legacy SQL Architect MCP?

**Works with legacy databases, not just modern ones.**
Full stored procedure source retrieval, trigger dependency mapping, and impact analysis designed for databases that have been running for years or decades — including large PL/SQL packages that other tools fail on.

**Read-only by design.**
Connects in strict read-only mode. Physically cannot modify your database — no INSERT, no UPDATE, no DELETE. Safe to point at production.

**No framework lock-in. No Docker.**
Pure Java + JDBC. No Spring, no ORM, no infrastructure to maintain. Runs as a lightweight native installer on Windows, macOS, and Linux.

**Combines structure + logic + real data in one conversation.**
The AI inspects the schema, reads the stored procedure that processes orders, then samples real orders to understand why something is broken — without you copy-pasting anything between tools.

**The AI understands relationships, not just tables.**
`dependency_graph` and `find_impact` trace how tables, triggers, procedures, views, and foreign keys connect — so the AI tells you *what breaks* before you change anything.

**Generates working Java code, not pseudocode.**
`generate_java_dao` produces ready-to-compile Entity + Repository classes (plain JDBC, Java 17+) directly from your live schema.

---

## Supported Databases

| Database | Tested version |
|----------|---------------|
| PostgreSQL | 11+ |
| SQL Server | 2016+ (Express, Standard, Enterprise) |
| Oracle | 12c+ |

---

## Available Tools

| Tool | What it does |
|------|-------------|
| `inspect_schema` | Tables, columns, types, primary keys, foreign keys |
| `data_sampler` | Sample rows with automatic sensitive-column masking |
| `get_procedure_source` | Full source code of stored procedures and functions |
| `query_plan_expert` | Execution plan analysis, full table scan detection |
| `dependency_graph` | FK chains, trigger→procedure call chains, view dependencies |
| `generate_mermaid_erd` | Entity-Relationship Diagram in Mermaid format |
| `generate_documentation` | Complete Markdown documentation for the entire schema |
| `find_impact` | Everything that depends on a given table |
| `generate_java_dao` | Java Entity + Repository classes (plain JDBC) per table |

---

## Quick Start

### 1. Install

**Windows** — download and run `legacy-sql-architect-mcp-1.0.0.exe`

**Linux (Debian/Ubuntu)**
```bash
sudo dpkg -i legacy-sql-architect-mcp-1.0.0.deb
```

**Linux (RHEL/Fedora)**
```bash
sudo rpm -i legacy-sql-architect-mcp-1.0.0.rpm
```

**macOS / JAR** — requires Java 21+
```bash
java -jar legacy-sql-architect-mcp.jar
```

### 2. Configure the database connection

Edit `config.json` in the installation directory:

```json
{
  "db_type": "postgresql",
  "db_url": "jdbc:postgresql://localhost:5432/your_database",
  "db_user": "your_user",
  "db_password": "your_password",
  "db_schema": "public",
  "data_sampler_rows": 10,
  "data_sampler_mask_sensitive": true
}
```

`db_type` accepts: `postgresql`, `sqlserver`, `oracle`

### 3. Add to Claude Desktop

Edit `%APPDATA%\Claude\claude_desktop_config.json` (Windows) or `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "legacy-sql-architect": {
      "command": "C:\\Program Files\\LegacySQLArchitectMCP\\LegacySQLArchitectMCP.exe"
    }
  }
}
```

Restart Claude Desktop. A hammer icon (🔨) confirms the server is active.

### 4. Try it

> *"Inspect the schema and give me an overview of the data model."*

> *"Get the source of all stored procedures and summarize the business logic."*

> *"I need to rename a column in the orders table — use find_impact to tell me everything that would break."*

---

## Example Prompts

```
Inspect the schema, generate a Mermaid ERD, sample the orders table,
read all stored procedures, and produce complete Markdown documentation.
```

```
Analyze this query with query_plan_expert:
SELECT * FROM orders WHERE status = 'PENDING' AND created_at > '2024-01-01'
Is it using indexes? What would you recommend?
```

```
Generate Java DAO classes for all tables using package com.acme.repository,
then write each file into src/main/java/com/acme/repository/.
```

```
Build the dependency graph. When a row is inserted into orders,
what triggers fire and what procedures do they call?
```

---

## Documentation

- [Setup Guide](docs/setup-guide.md) — installation, config.json reference, Claude Desktop setup, troubleshooting
- [User Guide](docs/user-guide.md) — all tools with prompt examples, multi-tool workflows, tips

---

## Building from Source

Requires Java 21 and Maven 3.8+.

```bash
# Run tests + build JAR
mvn clean package

# Windows installer (run on Windows, requires Java 21 with jpackage)
JAVA_HOME="c:/Java_21" mvn clean package -P windows-exe -Dmaven.test.skip=true

# Linux DEB (run inside Ubuntu/Debian container or system)
mvn clean package -P linux-deb -Dmaven.test.skip=true

# Linux RPM (run inside RHEL/Fedora container or system)
mvn clean package -P linux-rpm -Dmaven.test.skip=true
```

Integration tests use embedded PostgreSQL — no Docker, no external database required.

---

## Maven Central

The only MCP server that gives AI agents end-to-end understanding of legacy databases — schema structure, stored procedure logic, real data, and full dependency chains — all in a single conversation, without copying a single line of SQL.

```xml
<dependency>
    <groupId>io.github.tabforgeai</groupId>
    <artifactId>legacy-sql-architect-mcp</artifactId>
    <version>1.0.0</version>
</dependency>
```

*(Coming soon)*

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)
