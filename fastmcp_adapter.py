from mcp.server.fastmcp import FastMCP
import requests
from requests.exceptions import ConnectionError
import sys

mcp = FastMCP("Jadx MCP Server")

MCP_SERVER = "http://localhost:8085"

def invoke_jadx(tool, parameters={}):
    try:
        resp = requests.post(f"{MCP_SERVER}/invoke", json={"tool": tool, "parameters": parameters})
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
def list_all_classes() -> list:
    result = invoke_jadx("list_all_classes")
    return result

@mcp.tool()
def get_class_source(class_name: str) -> str:
    result = invoke_jadx("get_class_source", {"class_name": class_name})
    return result

@mcp.tool()
def search_method_by_name(method_name: str) -> str:
    result = invoke_jadx("search_method_by_name", {"method_name": method_name})
    return result

@mcp.tool()
def get_methods_of_class(class_name: str) -> list:
    result = invoke_jadx("get_methods_of_class", {"class_name": class_name})
    return result

@mcp.tool()
def get_fields_of_class(class_name: str) -> list:
    result = invoke_jadx("get_fields_of_class", {"class_name": class_name})
    return result

@mcp.tool()
def get_method_code(class_name: str, method_name: str) -> str:
    result = invoke_jadx("get_method_code", {"class_name": class_name, "method_name": method_name})
    return result

@mcp.resource("jadx://tools")
def get_tools_resource() -> dict:
    try:
        resp = requests.get(f"{MCP_SERVER}/tools")
        resp.raise_for_status()
        return resp.json()
    except ConnectionError:
        raise ConnectionError("Jadx MCP server is not running. Please start Jadx and try again.")
    except Exception as e:
        raise RuntimeError(f"Unexpected error: {str(e)}")

if __name__ == "__main__":
    mcp.run(transport="stdio")
    print("Adapter started", file=sys.stderr)
