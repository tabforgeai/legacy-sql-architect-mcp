# Legacy SQL Architect MCP — Setup Guide

## Table of Contents

1. [System Requirements](#1-system-requirements)
2. [Installation](#2-installation)
3. [config.json — Database Connection](#3-configjson--database-connection)
4. [Log Files](#4-log-files)
5. [Configuring Claude Desktop](#5-configuring-claude-desktop)
6. [Configuring Other AI Clients](#6-configuring-other-ai-clients)
7. [Verifying the Installation](#7-verifying-the-installation)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. System Requirements

| Component        | Minimum                                  |
|------------------|------------------------------------------|
| Operating System | Windows 10/11, macOS 12+, Ubuntu 20.04+ |
| Java             | Not required — bundled with the installer |
| Database         | PostgreSQL 11+, SQL Server 2016+, Oracle 12c+ |
| AI Client        | Claude Desktop, or any MCP-compatible client |

---

## 2. Installation

### Windows

1. Download `legacy-sql-architect-mcp-1.0.0.exe`
2. Run the installer — it installs to `C:\Program Files\Legacy SQL Architect MCP\` by default
3. After installation, the following files will be present:

```
C:\Program Files\Legacy SQL Architect MCP\
├── app\
│   ├── legacy-sql-architect-mcp.jar   ← application JAR
│   └── config.json                    ← database connection config (edit this)
├── runtime\                           ← bundled Java runtime (no JDK needed)
├── logs\                              ← log files written here automatically
└── legacy-sql-architect-mcp.exe       ← launcher
```

### macOS

macOS native installer (.dmg) is planned for a future release. For now, use the JAR directly (requires Java 21+):

1. Install Java 21: [https://adoptium.net](https://adoptium.net)
2. Download `legacy-sql-architect-mcp.jar`
3. Create a `config.json` file next to the JAR (see section 3 below)
4. Run:

```bash
java -jar legacy-sql-architect-mcp.jar
```

Config and logs are located next to the JAR file.

### Linux (Debian/Ubuntu)

```bash
sudo dpkg -i legacy-sql-architect-mcp-1.0.0.deb
```

Installed to:
```
/opt/legacy-sql-architect-mcp/
├── bin/
│   └── legacy-sql-architect-mcp      ← launcher
└── lib/
    ├── app/
    │   ├── config.json               ← database connection config (edit this)
    │   └── legacy-sql-architect-mcp.jar
    └── runtime/                      ← bundled Java runtime (no JDK needed)
```

### Linux (Red Hat/Fedora)

```bash
sudo rpm -i legacy-sql-architect-mcp-1.0.0.rpm
```

Installed to the same structure as Debian above (`/opt/legacy-sql-architect-mcp/`).

---

## 3. config.json — Database Connection

After installation, open `config.json` in a text editor and fill in your database credentials. The file is located in the `app\` subfolder of the installation directory.

### PostgreSQL

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

### SQL Server

```json
{
  "db_type": "sqlserver",
  "db_url": "jdbc:sqlserver://localhost:1433;databaseName=your_database;encrypt=true;trustServerCertificate=true",
  "db_user": "your_user",
  "db_password": "your_password",
  "db_schema": "dbo",
  "data_sampler_rows": 10,
  "data_sampler_mask_sensitive": true
}
```

### Oracle

```json
{
  "db_type": "oracle",
  "db_url": "jdbc:oracle:thin:@//localhost:1521/ORCLPDB1",
  "db_user": "your_user",
  "db_password": "your_password",
  "db_schema": "YOUR_SCHEMA",
  "data_sampler_rows": 10,
  "data_sampler_mask_sensitive": true
}
```

### config.json Field Reference

| Field                      | Required | Description |
|----------------------------|----------|-------------|
| `db_type`                  | Yes      | `postgresql`, `sqlserver`, or `oracle` |
| `db_url`                   | Yes      | JDBC connection URL |
| `db_user`                  | Yes      | Database username |
| `db_password`              | Yes      | Database password |
| `db_schema`                | Yes      | Default schema/owner to inspect |
| `data_sampler_rows`        | No       | Max rows returned per table by data_sampler (default: 10) |
| `data_sampler_mask_sensitive` | No    | Mask columns named password/token/secret/ssn/card (default: true) |

> **Security note:** The server connects in **read-only mode** — it cannot INSERT, UPDATE, or DELETE data in your database. Only SELECT and metadata queries are executed.

---

## 4. Log Files

Log files are written automatically. The location depends on how the server is started:

| Scenario                        | Log location |
|---------------------------------|-------------|
| Installed via native installer  | `logs\` folder next to the `app\` folder in the install directory |
| Run directly from JAR           | `logs\` folder in the same directory as the JAR |
| Development / Maven             | `logs\` in the current working directory |

**Windows example:**
```
C:\Program Files\Legacy SQL Architect MCP\logs\legacy-sql-architect-mcp.log
```

The log file is rolled daily. If you encounter any problems, check the log for error details before raising a support issue.

> **Important:** All output is written to STDERR and the log file. STDOUT is reserved for the MCP protocol communication — do not redirect it.

---

## 5. Configuring Claude Desktop

Claude Desktop uses a JSON configuration file to register MCP servers.

### Location of claude_desktop_config.json

| OS      | Path |
|---------|------|
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| macOS   | `~/Library/Application Support/Claude/claude_desktop_config.json` |

### Windows — installed via .exe

```json
{
  "mcpServers": {
    "legacy-sql-architect": {
      "command": "C:\\Program Files\\Legacy SQL Architect MCP\\legacy-sql-architect-mcp.exe",
      "args": []
    }
  }
}
```

### macOS — JAR (requires Java 21+)

```json
{
  "mcpServers": {
    "legacy-sql-architect": {
      "command": "java",
      "args": ["-jar", "/path/to/legacy-sql-architect-mcp.jar"]
    }
  }
}
```

### Linux — installed via .deb or .rpm

```json
{
  "mcpServers": {
    "legacy-sql-architect": {
      "command": "/opt/legacy-sql-architect-mcp/bin/legacy-sql-architect-mcp",
      "args": []
    }
  }
}
```

### Run directly from JAR (development / no installer)

Requires Java 21+ on PATH.

```json
{
  "mcpServers": {
    "legacy-sql-architect": {
      "command": "java",
      "args": ["-jar", "/path/to/legacy-sql-architect-mcp.jar"]
    }
  }
}
```

After editing the file, **restart Claude Desktop** for the changes to take effect. A hammer icon (🔨) in the Claude Desktop chat window confirms the MCP server is active.

---

## 6. Configuring Other AI Clients

Any MCP-compatible client can use this server. The server communicates over **stdio** using the Model Context Protocol.

### Generic MCP stdio configuration

The server is started as a subprocess. The client must:
- Launch the executable (or `java -jar legacy-sql-architect-mcp.jar`)
- Communicate via stdin/stdout using the MCP JSON-RPC protocol
- Treat stderr as diagnostic output (logs)

### Cursor IDE

Add to `.cursor/mcp.json` in your project root (or the global Cursor MCP config):

```json
{
  "mcpServers": {
    "legacy-sql-architect": {
      "command": "C:\\Program Files\\Legacy SQL Architect MCP\\legacy-sql-architect-mcp.exe",
      "args": []
    }
  }
}
```

### Windsurf / Codeium

Add to the Windsurf MCP configuration (Settings → MCP Servers):

```json
{
  "legacy-sql-architect": {
    "command": "/path/to/legacy-sql-architect-mcp",
    "args": [],
    "transport": "stdio"
  }
}
```

### Custom / Programmatic MCP Client

Start the server as a subprocess and communicate via stdin/stdout:

```bash
# Start the server
java -jar legacy-sql-architect-mcp.jar

# The server announces its tools via MCP initialize handshake
# then accepts tool call requests in JSON-RPC format
```

---

## 7. Verifying the Installation

After configuring Claude Desktop:

1. Open Claude Desktop
2. Start a new conversation
3. Type:

   > **"List all tools you have available."**

   Claude should list the 9 Legacy SQL Architect tools (inspect_schema, data_sampler, etc.)

4. Type:

   > **"Use the inspect_schema tool to show me the tables in my database."**

   If the connection is configured correctly, Claude will return a list of tables from your database.

If no tools appear, check:
- The path in `claude_desktop_config.json` is correct
- `config.json` has valid credentials
- The log file for error details

---

## 8. Troubleshooting

### "No tools available" in Claude Desktop

- Verify the path in `claude_desktop_config.json` points to the correct executable
- On Windows, use double backslashes (`\\`) in JSON paths
- Restart Claude Desktop after any config change

### "Connection refused" or database error

- Check `db_url`, `db_user`, `db_password` in `config.json`
- Ensure the database server is running and reachable from this machine
- Check firewall rules (port 5432 for PostgreSQL, 1433 for SQL Server, 1521 for Oracle)
- View the log file for the full error message and stack trace

### Oracle: schema returns no objects

- Oracle stores object names in UPPERCASE by default
- Use uppercase in `db_schema`: `"db_schema": "MY_SCHEMA"` (not `"my_schema"`)

### SQL Server: connection SSL error

- Add `trustServerCertificate=true` to the JDBC URL for self-signed certificates:
  `"db_url": "jdbc:sqlserver://host:1433;databaseName=db;encrypt=true;trustServerCertificate=true"`

### Log file is empty or not created

- Ensure the process has write access to the `logs\` directory
- On Linux/macOS, check folder permissions: `chmod 755 /opt/legacy-sql-architect-mcp/`
