## ⚙️ Jadx MCP Plugin — Decompiler Access for Claude via MCP

This project provides a [Jadx](https://github.com/skylot/jadx) plugin written in **Java**, which exposes the **Jadx API over HTTP** — enabling live interaction through [Claude](https://www.anthropic.com/index/introducing-claude) via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/). A lightweight [FastMCP](https://github.com/pacocoursey/fastmcp) adapter in Python acts as a bridge between Claude and the plugin.

---

### 🧰 Setup Instructions

```bash
# Clone this repository
git clone https://github.com/mobilehackinglab/jadx-mcp-plugin.git
cd jadx-mcp-plugin

# Create and activate a virtual environment
python3 -m venv venv

# Activate:
source venv/bin/activate      # Linux/Mac
.\venv\Scripts\activate       # Windows
```

### Install Python dependencies
```bash
pip install -r requirements.txt 
```

### 🧠 Setup Claude MCP CLient Integration
To use this adapter in Claude Desktop, go to `File` -> `Settings` -> `Developer` -> `Edit Config` -> `claude_desktop_config.json` and add an MCP server pointing to the python executable in the venv (to prevent depedency issues) and the full adapater path following below examples:

Windows:

```json
{
  "mcpServers": {
    "ghidra": {
      "command": "python",
      "args": [
        "C:\\Workset\\jadx-mcp-plugin\\venv\\Scripts\\python.exe",
        "C:\\Workset\\jadx-mcp-plugin\\fastmcp_adapter.py"
      ]
    }
  }
}
```

MacOS / Linux:
```json
{
  "mcpServers": {
    "Jadx MCP Server": {
      "command": "/Users/yourname/jadx-mcp-plugin/venv/bin/python",
      "args": ["/Users/yourname/jadx-mcp-plugin/fastmcp_adapter.py"]
    }
  }
}
```

Make sure to restart (Quit) Claude after editing the config.
After restart it should look like this:
![](img/jadx-mcp-running.png)

### ✅ Usage Flow

1. Open **Jadx** with the latest plugin JAR from [the releases](https://github.com/mobilehackinglab/jadx-mcp-plugin/releases) placed in its `plugins/` folder or load it via `Plugins` -> `install plugin`.
2. Load an APK or DEX file
3. Claude will detect and activate the **Jadx MCP Server** tools
4. You can now list classes, fetch source, inspect methods/fields, and extract code live

---

## 🧪 Tools Provided

| Tool                  | Description                           |
|-----------------------|---------------------------------------|
| `list_all_classes`    | Get all decompiled class names        |
| `get_class_source`    | Get full source of a given class      |
| `search_method_by_name` | Find methods matching a string     |
| `get_methods_of_class` | List all method names in a class     |
| `get_fields_of_class`  | List all field names in a class      |
| `get_method_code`     | Extract decompiled code for a method  |

---

## 🛠 Development

### Java Plugin

The Java plugin is located at:

```
plugin/src/main/java/com/mobilehackinglab/jadxplugin/McpPlugin.java
```

It uses the `JadxPlugin` API (`jadx.api.*`) to:
- Load decompiled classes and methods
- Serve structured data via an embedded HTTP server
- Respond to `/invoke` and `/tools` endpoints

To build the plugin:

```bash
./gradlew build
# Output: plugin/build/libs/jadx-mcp-plugin-<version>-all.jar
```

Place the `.jar` in your Jadx `plugins/` folder.

---

### Python FastMCP Adapter

The adapter file is:

```
fastmcp_adapter.py
```

It translates Claude’s MCP tool calls into HTTP POSTs to the running Jadx plugin server. Make sure Jadx is open **before** starting Claude.

---

## 🤝 Contributing

PRs, feature requests, and tool extensions are welcome!  
This project is maintained by [Mobile Hacking Lab](https://github.com/mobilehackinglab).

---

## 🧩 Credits

- [Jadx](https://github.com/skylot/jadx)
- [FastMCP](https://github.com/jlowin/fastmcp)
- [Claude by Anthropic](https://www.anthropic.com)
