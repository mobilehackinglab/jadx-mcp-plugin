package com.mobilehackinglab.jadxplugin;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class McpPlugin implements JadxPlugin {
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private JadxPluginContext context;
    private boolean running = false;
    private final int port = 8085;

    public McpPlugin() {
    }

    /**
     * Called by Jadx to initialize the plugin.
     */
    @Override
    public void init(JadxPluginContext context) {
        this.context = context;
        new Thread(this::safePluginStartup).start();
    }

    /**
     * Starts the HTTP server if Jadx is ready.
     */
    private void safePluginStartup() {
        if (!waitForJadxLoad()) {
            System.err.println("[MCP] Jadx initialization failed. Not starting server.");
            return;
        }

        try {
            startServer();
            System.out.println("[MCP] Server started successfully at http://localhost:" + port);
        } catch (IOException e) {
            System.err.println("[MCP] Failed to start server: " + e.getMessage());
        }
    }

    /**
     * Waits for the Jadx decompiler to finish loading classes.
     */
    private boolean waitForJadxLoad() {
        int retries = 0;
        while (retries < 30) {
            if (isDecompilerValid()) {
                int count = context.getDecompiler().getClassesWithInners().size();
                System.out.println("[MCP] Jadx fully loaded. Classes found: " + count);
                return true;
            }

            System.out.println("[MCP] Waiting for Jadx to finish loading classes...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }

            retries++;
        }

        System.err.println("[MCP] Jadx failed to load classes within expected time.");
        return false;
    }

    /**
     * Provides metadata for the plugin to Jadx.
     */
    @Override
    public JadxPluginInfo getPluginInfo() {
        return new JadxPluginInfo(
                "jadx-mcp",
                "JADX MCP Plugin",
                "Exposes Jadx info over HTTP",
                "https://github.com/mobilehackinglab/jadx-mcp-plugin",
                "1.0.0");
    }

    /**
     * Cleanly shuts down the server and executor.
     */
    public void destroy() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Starts the TCP server and accepts incoming connections.
     */
    private void startServer() throws IOException {
        serverSocket = new ServerSocket(port);
        executor = Executors.newFixedThreadPool(5);
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running)
                        System.err.println("[MCP] Error accepting connection: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * Handles incoming HTTP requests to the plugin.
     */
    private void handleConnection(Socket socket) {
        try (socket;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream outStream = socket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null)
                return;

            String method = requestLine.split(" ")[0];
            String path = requestLine.split(" ")[1];

            int contentLength = 0;
            String header;
            while (!(header = in.readLine()).isEmpty()) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
                }
            }

            char[] buffer = new char[contentLength];
            int bytesRead = in.read(buffer);
            String body = new String(buffer, 0, bytesRead);

            JSONObject responseJson;

            if ("/invoke".equals(path) && "POST".equalsIgnoreCase(method)) {
                try {
                    String result = processInvokeRequest(body);
                    if (result.trim().startsWith("{")) {
                        responseJson = new JSONObject(result);
                    } else {
                        responseJson = new JSONObject().put("result", result);
                    }
                } catch (Exception e) {
                    responseJson = new JSONObject().put("error", "Failed to process tool: " + e.getMessage());
                }
            } else if ("/tools".equals(path)) {
                responseJson = new JSONObject(getToolsJson());
            } else {
                responseJson = new JSONObject().put("error", "Not found");
            }

            byte[] respBytes = responseJson.toString(2).getBytes(StandardCharsets.UTF_8);

            PrintWriter out = new PrintWriter(outStream, true);
            out.printf(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
                    respBytes.length);
            out.flush();

            outStream.write(respBytes);
            outStream.flush();

        } catch (Exception e) {
            System.err.println("[MCP] Error handling connection: " + e.getMessage());
        }
    }

    /**
     * Return available tools for MCP server in JSON
     */
    private String getToolsJson() {
        return """
                {
                    "tools": [
                        { "name": "get_class_source", "description": "Returns the decompiled source of a class.", "parameters": { "class_name": "string" } },
                        { "name": "search_method_by_name", "description": "Search methods by name.", "parameters": { "method_name": "string" } },
                        { "name": "search_class_by_name", "description": "Search class names containing a keyword.", "parameters": { "query": "string" } },
                        { "name": "list_all_classes", "description": "Returns a list of all class names.", "parameters": { "offset": "int", "limit": "int" } },
                        { "name": "get_methods_of_class", "description": "Returns all method names of a class.", "parameters": { "class_name": "string" } },
                        { "name": "get_fields_of_class", "description": "Returns all field names of a class.", "parameters": { "class_name": "string" } },
                        { "name": "get_method_code", "description": "Returns the code for a specific method.", "parameters": { "class_name": "string", "method_name": "string" } }
                    ]
                }
                """;
    }

    /**
     * Handles tool invocation from the client, routing to the correct handler.
     *
     * @param requestBody JSON request with tool and parameters
     * @return JSON response string
     * @throws JSONException if the input JSON is malformed
     */
    private String processInvokeRequest(String requestBody) throws JSONException {
        JSONObject requestJson = new JSONObject(requestBody);
        String toolName = requestJson.getString("tool");
        JSONObject params = requestJson.optJSONObject("parameters");
        if (params == null)
            params = new JSONObject();

        return switch (toolName) {
            case "get_class_source" -> handleGetClassSource(params);
            case "search_method_by_name" -> handleSearchMethodByName(params);
            case "search_class_by_name" -> handleSearchClassByName(params);
            case "list_all_classes" -> handleListAllClasses(params);
            case "get_methods_of_class" -> handleGetMethodsOfClass(params);
            case "get_fields_of_class" -> handleGetFieldsOfClass(params);
            case "get_method_code" -> handleGetMethodCode(params);
            default -> new JSONObject().put("error", "Unknown tool: " + toolName).toString();
        };
    }

    /**
     * Search class names based on a partial query string and return matches.
     *
     * @param params JSON object with key "query"
     * @return JSON object with array of matched class names under "results"
     */
    private String handleSearchClassByName(JSONObject params) {
        String query = params.optString("query", "").toLowerCase();
        JSONArray array = new JSONArray();
        for (JavaClass cls : context.getDecompiler().getClassesWithInners()) {
            String fullName = cls.getFullName();
            if (fullName.toLowerCase().contains(query)) {
                array.put(fullName);
            }
        }
        return new JSONObject().put("results", array).toString();
    }

    /**
     * Retrieves the full decompiled source code of a specific Java class.
     *
     * @param params A JSON object containing the required parameter:
     *               - "class_name": The fully qualified name of the class to
     *               retrieve.
     * @return The decompiled source code of the class, or an error message if not
     *         found.
     */
    private String handleGetClassSource(JSONObject params) {
        try {
            String className = params.getString("class_name");
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    return cls.getCode();
                }
            }
            return "Class not found: " + className;
        } catch (Exception e) {
            return "Error fetching class: " + e.getMessage();
        }
    }

    /**
     * Searches all decompiled classes for methods whose names match or contain the
     * provided string.
     *
     * @param params A JSON object containing the required parameter:
     *               - "method_name": A case-insensitive string to match method
     *               names.
     * @return A newline-separated list of matched methods with their class names,
     *         or a message if no match is found.
     */
    private String handleSearchMethodByName(JSONObject params) {
        try {
            String methodName = params.getString("method_name");
            StringBuilder result = new StringBuilder();
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                cls.decompile();
                for (JavaMethod method : cls.getMethods()) {
                    if (method.getName().toLowerCase().contains(methodName.toLowerCase())) {
                        result.append(cls.getFullName()).append(" -> ").append(method.getName()).append("\n");
                    }
                }
            }
            return result.length() > 0 ? result.toString() : "No methods found for: " + methodName;
        } catch (Exception e) {
            return "Error searching methods: " + e.getMessage();
        }
    }

    /**
     * Lists all classes with optional pagination.
     *
     * @param params JSON with optional offset and limit
     * @return JSON response with class list and metadata
     */
    private String handleListAllClasses(JSONObject params) {
        int offset = params.optInt("offset", 0);
        int limit = params.optInt("limit", 250);
        int maxLimit = 500;
        if (limit > maxLimit)
            limit = maxLimit;

        List<JavaClass> allClasses = context.getDecompiler().getClassesWithInners();
        int total = allClasses.size();

        JSONArray array = new JSONArray();
        for (int i = offset; i < Math.min(offset + limit, total); i++) {
            JavaClass cls = allClasses.get(i);
            array.put(cls.getFullName());
        }

        JSONObject response = new JSONObject();
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("classes", array);

        return response.toString();
    }

    /**
     * Retrieves a list of all method names declared in the specified Java class.
     *
     * @param params A JSON object containing the required parameter:
     *               - "class_name": The fully qualified name of the class.
     * @return A formatted JSON array of method names, or an error message if the
     *         class is not found.
     */
    private String handleGetMethodsOfClass(JSONObject params) {
        try {
            String className = params.getString("class_name");
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    JSONArray array = new JSONArray();
                    for (JavaMethod method : cls.getMethods()) {
                        array.put(method.getName());
                    }
                    return array.toString(2);
                }
            }
            return "Class not found: " + className;
        } catch (Exception e) {
            return "Error fetching methods: " + e.getMessage();
        }
    }

    /**
     * Retrieves all field names defined in the specified Java class.
     *
     * @param params A JSON object containing the required parameter:
     *               - "class_name": The fully qualified name of the class.
     * @return A formatted JSON array of field names, or an error message if the
     *         class is not found.
     */
    private String handleGetFieldsOfClass(JSONObject params) {
        try {
            String className = params.getString("class_name");
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    JSONArray array = new JSONArray();
                    for (JavaField field : cls.getFields()) {
                        array.put(field.getName());
                    }
                    return array.toString(2);
                }
            }
            return "Class not found: " + className;
        } catch (Exception e) {
            return "Error fetching fields: " + e.getMessage();
        }
    }

    /**
     * Extracts the decompiled source code of a specific method within a given
     * class.
     *
     * @param params A JSON object containing the required parameters:
     *               - "class_name": The fully qualified name of the class.
     *               - "method_name": The name of the method to extract.
     * @return A string containing the method's source code block (if found),
     *         or a descriptive error message if the method or class is not found.
     */
    private String handleGetMethodCode(JSONObject params) {
        try {
            String className = params.getString("class_name");
            String methodName = params.getString("method_name");
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    cls.decompile();
                    for (JavaMethod method : cls.getMethods()) {
                        if (method.getName().equals(methodName)) {
                            String classCode = cls.getCode();
                            int methodIndex = classCode.indexOf(method.getName());
                            if (methodIndex != -1) {
                                int openBracket = classCode.indexOf('{', methodIndex);
                                if (openBracket != -1) {
                                    int closeBracket = findMatchingBracket(classCode, openBracket);
                                    if (closeBracket != -1) {
                                        String methodCode = classCode.substring(openBracket, closeBracket + 1);
                                        return methodCode;
                                    }
                                }
                            }

                            return "Could not extract method code from class source.";
                        }
                    }
                    return "Method '" + methodName + "' not found in class '" + className + "'";
                }
            }
            return "Class '" + className + "' not found";
        } catch (Exception e) {
            return "Error fetching method code: " + e.getMessage();
        }
    }

    // Helper method to find matching closing bracket
    private int findMatchingBracket(String code, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1; // No matching bracket found
    }

    /**
     * Validates that the decompiler is loaded and usable.
     * This is needed because: When you use "File → Open" to load a new file,
     * Jadx replaces the internal decompiler instance, but your plugin still holds a
     * stale reference to the old one.
     */
    private boolean isDecompilerValid() {
        try {
            return context != null
                    && context.getDecompiler() != null
                    && context.getDecompiler().getRoot() != null
                    && !context.getDecompiler().getClassesWithInners().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}