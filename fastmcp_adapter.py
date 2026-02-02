from mcp.server.fastmcp import FastMCP
import requests
from requests.exceptions import ConnectionError
import sys

# Create the MCP adapter with a human-readable name
mcp = FastMCP("Jadx MCP Server")

# Address of the Jadx MCP plugin's HTTP server
DEFAULT_MCP_SERVER = "http://localhost:8085"
mcp_server = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MCP_SERVER

def invoke_jadx(tool: str, parameters: dict = {}) -> dict:
    """
    Internal helper to send a tool request to the Jadx MCP HTTP server.
    """
    try:
        resp = requests.post(f"{mcp_server}/invoke", json={"tool": tool, "parameters": parameters})
        resp.raise_for_status()
        data = resp.json()
        if "error" in data:
            raise ValueError(data["error"])
        return data.get("result", data)
    except ConnectionError:
        raise ConnectionError("Jadx MCP server is not running. Please start Jadx and try again.")
    except Exception as e:
        raise RuntimeError(f"Unexpected error: {str(e)}")


@mcp.tool()
def list_all_classes(limit: int = 250, offset: int = 0) -> dict:
    """
    Returns a paginated list of class names.

    Params:
    - limit: Max number of classes to return (default 250)
    - offset: Starting index of class list
    """
    return invoke_jadx("list_all_classes", {"limit": limit, "offset": offset})


@mcp.tool()
def search_class_by_name(query: str) -> dict:
    """Search for class names that contain the given query string (case-insensitive)."""
    return invoke_jadx("search_class_by_name", {"query": query})


@mcp.tool()
def get_class_source(class_name: str) -> str:
    """
   Returns the full decompiled source code of a given class.
    """
    return invoke_jadx("get_class_source", {"class_name": class_name})


@mcp.tool()
def search_method_by_name(method_name: str) -> str:
    """
   Searches for all methods matching the provided name.
   Returns class and method pairs as string.
    """
    return invoke_jadx("search_method_by_name", {"method_name": method_name})


@mcp.tool()
def get_methods_of_class(class_name: str) -> list:
    """
   Returns all method names declared in the specified class.
    """
    return invoke_jadx("get_methods_of_class", {"class_name": class_name})


@mcp.tool()
def get_fields_of_class(class_name: str) -> list:
    """
   Returns all field names declared in the specified class.
    """
    return invoke_jadx("get_fields_of_class", {"class_name": class_name})


@mcp.tool()
def get_method_code(class_name: str, method_name: str) -> str:
    """
   Returns only the source code block of a specific method within a class.
    """
    return invoke_jadx("get_method_code", {
        "class_name": class_name,
        "method_name": method_name
    })

@mcp.tool()
def get_android_manifest() -> str:
    """
   Returns the content of AndroidManifest.xml
    """
    return invoke_jadx("get_android_manifest")


@mcp.tool()
def get_all_resource_file_names() -> dict:
    """
    Returns a list of all resource file names in the APK.
    """
    return invoke_jadx("get_all_resource_file_names")


@mcp.tool()
def get_resource_file(resource_name: str) -> dict:
    """
    Returns the content of a specific resource file.
    """
    return invoke_jadx("get_resource_file", {"resource_name": resource_name})


@mcp.tool()
def get_class_xrefs(class_name: str) -> dict:
    """
    Returns all references to a class.
    """
    return invoke_jadx("get_class_xrefs", {"class_name": class_name})


@mcp.tool()
def get_method_xrefs(class_name: str, method_name: str) -> dict:
    """
    Returns all references to a method.
    """
    return invoke_jadx("get_method_xrefs", {
        "class_name": class_name,
        "method_name": method_name
    })


@mcp.tool()
def get_field_xrefs(class_name: str, field_name: str) -> dict:
    """
    Returns all references to a field.
    """
    return invoke_jadx("get_field_xrefs", {
        "class_name": class_name,
        "field_name": field_name
    })


@mcp.resource("jadx://tools")
def get_tools_resource() -> dict:
    """
   Fetches the list of all available tools and their descriptions from the Jadx plugin.
    Used for dynamic tool discovery.
    """
    try:
        resp = requests.get(f"{mcp_server}/tools")
        resp.raise_for_status()
        return resp.json()
    except ConnectionError:
        raise ConnectionError("Jadx MCP server is not running. Please start Jadx and try again.")
    except Exception as e:
        raise RuntimeError(f"Unexpected error: {str(e)}")

if __name__ == "__main__":
    mcp.run(transport="stdio")
    print("Adapter started", file=sys.stderr)
