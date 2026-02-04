package com.mobilehackinglab.jadxplugin;

import jadx.api.*;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.core.xmlgen.ResContainer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McpPlugin implements JadxPlugin {
    public static final String PLUGIN_ID = "jadx-mcp";

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private JadxPluginContext context;
    private McpPluginOptions pluginOptions;
    private boolean running = false;

    public McpPlugin() {
    }

    /**
     * Called by Jadx to initialize the plugin.
     */
    @Override
    public void init(JadxPluginContext context) {
        this.context = context;

        this.pluginOptions = new McpPluginOptions();
        this.context.registerOptions(this.pluginOptions);

        new Thread(this::safePluginStartup).start();
    }

    /**
     * Provides metadata for the plugin to Jadx.
     */
    @Override
    public JadxPluginInfo getPluginInfo() {
        return new JadxPluginInfo(
                PLUGIN_ID,
                "JADX MCP Plugin",
                "Exposes Jadx info over HTTP",
                "https://github.com/mobilehackinglab/jadx-mcp-plugin",
                "1.4.0");
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
            URL httpInterface = parseHttpInterface(pluginOptions.getHttpInterface());
            startServer(httpInterface);
            System.out.println("[MCP] Server started successfully at " + httpInterface.getProtocol() + "://" + httpInterface.getHost() + ":" + httpInterface.getPort());
        } catch (IOException | IllegalArgumentException e) {
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

    /**
     * Parses and validates the given HTTP interface string.
     *
     * <p>This method strictly enforces that the input must be a complete HTTP URL
     * with a protocol of {@code http}, a non-empty host, and an explicit port.</p>
     *
     * <p>Examples of valid input: {@code http://127.0.0.1:8080}, {@code http://localhost:3000}</p>
     *
     * @param httpInterface A string representing the HTTP interface.
     * @return A {@link URL} object representing the parsed interface.
     * @throws IllegalArgumentException if the URL is malformed or missing required components.
     */
    private URL parseHttpInterface(String httpInterface) throws IllegalArgumentException {
        URL url;
        try {
            url = new URL(httpInterface);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Malformed HTTP interface URL: " + httpInterface, e);
        }

        if (!"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("Invalid protocol: " + url.getProtocol() + ". Only 'http' is supported.");
        }

        if (url.getHost() == null || url.getHost().isEmpty()) {
            throw new IllegalArgumentException("Missing or invalid host in HTTP interface: " + httpInterface);
        }

        if (url.getPort() == -1) {
            throw new IllegalArgumentException("Port must be explicitly specified in HTTP interface: " + httpInterface);
        }

        if (url.getPath() != null && !url.getPath().isEmpty() && !url.getPath().equals("/")) {
            throw new IllegalArgumentException("Path is not allowed in HTTP interface: " + httpInterface);
        }

        if (url.getQuery() != null || url.getRef() != null || url.getUserInfo() != null) {
            throw new IllegalArgumentException("HTTP interface must not contain query, fragment, or user info: " + httpInterface);
        }

        return url;
    }

    /**
     * Starts the TCP server and accepts incoming connections.
     */
    private void startServer(URL httpInterface) throws IOException {
        String host = httpInterface.getHost();
        int port = httpInterface.getPort();
        InetAddress bindAddr = InetAddress.getByName(host);

        serverSocket = new ServerSocket(port, 50, bindAddr);
        executor = Executors.newFixedThreadPool(5);
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[MCP] Error accepting connection: " + e.getMessage());
                    }
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
            if (requestLine == null) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }

            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buffer = new char[contentLength];
                int bytesRead = in.read(buffer);
                body = new String(buffer, 0, bytesRead);
            }

            JSONObject responseJson;

            if ("/invoke".equals(path) && "POST".equalsIgnoreCase(method)) {
                responseJson = processInvokeRequest(body);
            } else if ("/tools".equals(path)) {
                responseJson = getToolsJson();
            } else {
                responseJson = errorJson("Not found");
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
            // At this point, response may be partially written; best-effort logging only.
        }
    }

    /**
     * Handles tool invocation from the client, routing to the correct handler.
     *
     * @param requestBody JSON request with tool and parameters
     * @return JSON response object
     */
    private JSONObject processInvokeRequest(String requestBody) {
        try {
            JSONObject requestJson = new JSONObject(requestBody);

            String toolName = requestJson.optString("tool", null);
            if (toolName == null || toolName.isEmpty()) {
                return errorJson("Missing required field 'tool'");
            }

            JSONObject params = requestJson.optJSONObject("parameters");
            if (params == null) {
                params = new JSONObject();
            }

            return switch (toolName) {
                // 1) Manifest
                case "get_android_manifest" -> handleGetAndroidManifest();

                // 2) Discover classes
                case "list_all_classes" -> handleListAllClasses(params);

                // 3) Search classes
                case "search_class_by_name" -> handleSearchClassByName(params);

                // 4) Inspect a class
                case "get_class_source" -> handleGetClassSource(params);
                case "get_methods_of_class" -> handleGetMethodsOfClass(params);
                case "get_fields_of_class" -> handleGetFieldsOfClass(params);

                // 5) Search methods
                case "search_method_by_name" -> handleSearchMethodByName(params);

                // 6) Inspect a specific method
                case "get_method_code" -> handleGetMethodCode(params);

                // 7) Resources
                case "get_all_resource_file_names" -> handleGetAllResourceFileNames(params);
                case "get_resource_file" -> handleGetResourceFile(params);

                // 8) Xrefs
                case "get_class_xrefs" -> handleGetClassXrefs(params);
                case "get_method_xrefs" -> handleGetMethodXrefs(params);
                case "get_field_xrefs" -> handleGetFieldXrefs(params);

                default -> errorJson("Unknown tool: " + toolName);
            };
        } catch (JSONException e) {
            return errorJson("Invalid JSON in request body: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("Unexpected error while processing request: " + e.getMessage());
        }
    }

    /**
     * Return available tools for MCP server in JSON.
     */
    private JSONObject getToolsJson() {
        JSONArray tools = new JSONArray();

        // 1) Get Manifest
        tools.put(new JSONObject()
                .put("name", "get_android_manifest")
                .put("description", "Returns the content of AndroidManifest.xml if available.")
                .put("parameters", new JSONObject()));

        // 2) Discover classes
        tools.put(new JSONObject()
                .put("name", "list_all_classes")
                .put("description", "Returns a list of all class names.")
                .put("parameters", new JSONObject()
                        .put("offset", "int")
                        .put("limit", "int")));

        // 3) Search classes
        tools.put(new JSONObject()
                .put("name", "search_class_by_name")
                .put("description", "Search class names containing a keyword.")
                .put("parameters", new JSONObject().put("query", "string")));

        // 4) Inspect a class
        tools.put(new JSONObject()
                .put("name", "get_class_source")
                .put("description", "Returns the decompiled source of a class.")
                .put("parameters", new JSONObject().put("class_name", "string")));

        tools.put(new JSONObject()
                .put("name", "get_methods_of_class")
                .put("description", "Returns all method names of a class.")
                .put("parameters", new JSONObject().put("class_name", "string")));

        tools.put(new JSONObject()
                .put("name", "get_fields_of_class")
                .put("description", "Returns all field names of a class.")
                .put("parameters", new JSONObject().put("class_name", "string")));

        // 5) Search methods
        tools.put(new JSONObject()
                .put("name", "search_method_by_name")
                .put("description", "Search methods by name.")
                .put("parameters", new JSONObject().put("method_name", "string")));

        // 6) Inspect a specific method
        tools.put(new JSONObject()
                .put("name", "get_method_code")
                .put("description", "Returns the code for a specific method.")
                .put("parameters", new JSONObject()
                        .put("class_name", "string")
                        .put("method_name", "string")));

        // 7) Resources
        tools.put(new JSONObject()
                .put("name", "get_all_resource_file_names")
                .put("description", "Returns a list of all resource file names.")
                .put("parameters", new JSONObject()
                        .put("offset", "int")
                        .put("limit", "int")));

        tools.put(new JSONObject()
                .put("name", "get_resource_file")
                .put("description", "Returns the content of a specific resource file.")
                .put("parameters", new JSONObject().put("resource_name", "string")));

        // 8) Xrefs
        tools.put(new JSONObject()
                .put("name", "get_class_xrefs")
                .put("description", "Returns all references to a class.")
                .put("parameters", new JSONObject().put("class_name", "string")));

        tools.put(new JSONObject()
                .put("name", "get_method_xrefs")
                .put("description", "Returns all references to a method.")
                .put("parameters", new JSONObject()
                        .put("class_name", "string")
                        .put("method_name", "string")));

        tools.put(new JSONObject()
                .put("name", "get_field_xrefs")
                .put("description", "Returns all references to a field.")
                .put("parameters", new JSONObject()
                        .put("class_name", "string")
                        .put("field_name", "string")));

        return new JSONObject().put("tools", tools);
    }

    /**
     * Small helper to create a standard error JSON object.
     */
    private JSONObject errorJson(String message) {
        JSONObject obj = new JSONObject();
        obj.put("error", message);
        return obj;
    }

    /**
     * Retrieves the content of AndroidManifest.xml
     * <p>
     * This extracts the decoded XML from the ResContainer returned by loadContent().
     *
     * @return The manifest XML as a string or an error message.
     */
    private JSONObject handleGetAndroidManifest() {
        try {
            for (ResourceFile resFile : context.getDecompiler().getResources()) {
                if (resFile.getType() == ResourceType.MANIFEST) {
                    ResContainer container = resFile.loadContent();
                    if (container.getText() != null) {
                        String manifestCode = container.getText().getCodeStr();
                        return new JSONObject()
                                .put("manifest", manifestCode);
                    } else {
                        return errorJson("Manifest content is empty or could not be decoded.");
                    }
                }
            }
            return errorJson("AndroidManifest.xml not found.");
        } catch (Exception e) {
            return errorJson("Error retrieving AndroidManifest.xml: " + e.getMessage());
        }
    }

    /**
     * Lists all classes with optional pagination.
     *
     * @param params JSON with optional offset and limit
     * @return JSON response with class list and metadata
     */
    private JSONObject handleListAllClasses(JSONObject params) {
        int offset = params.optInt("offset", 0);
        int limit = params.optInt("limit", 250);
        int maxLimit = 500;
        if (limit > maxLimit) {
            limit = maxLimit;
        }

        List<JavaClass> allClasses = context.getDecompiler().getClassesWithInners();
        int total = allClasses.size();

        JSONArray array = new JSONArray();
        for (int i = offset; i < Math.min(offset + limit, total); i++) {
            JavaClass cls = allClasses.get(i);
            array.put(cls.getFullName());
        }

        return new JSONObject()
                .put("total", total)
                .put("offset", offset)
                .put("limit", limit)
                .put("classes", array);
    }

    /**
     * Search class names based on a partial query string and return matches.
     *
     * @param params JSON object with key "query"
     * @return JSON object with array of matched class names under "results"
     */
    private JSONObject handleSearchClassByName(JSONObject params) {
        String query = params.optString("query", "").toLowerCase();
        JSONArray array = new JSONArray();

        for (JavaClass cls : context.getDecompiler().getClassesWithInners()) {
            String fullName = cls.getFullName();
            if (fullName.toLowerCase().contains(query)) {
                array.put(fullName);
            }
        }

        return new JSONObject()
                .put("query", query)
                .put("results", array);
    }

    /**
     * Retrieves the full decompiled source code of a specific Java class.
     *
     * @param params A JSON object containing the required parameter:
     *               - "class_name": The fully qualified name of the class to
     *               retrieve.
     */
    private JSONObject handleGetClassSource(JSONObject params) {
        String className = params.optString("class_name", null);
        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    String code = cls.getCode();
                    return new JSONObject()
                            .put("class_name", className)
                            .put("source", code);
                }
            }
            return errorJson("Class not found: " + className);
        } catch (Exception e) {
            return errorJson("Error fetching class: " + e.getMessage());
        }
    }

    /**
     * Retrieves a list of all method names declared in the specified Java class.
     *
     * @param params A JSON object containing the required parameter:
     *               - "class_name": The fully qualified name of the class.
     */
    private JSONObject handleGetMethodsOfClass(JSONObject params) {
        String className = params.optString("class_name", null);
        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    JSONArray array = new JSONArray();
                    for (JavaMethod method : cls.getMethods()) {
                        array.put(method.getName());
                    }
                    return new JSONObject()
                            .put("class_name", className)
                            .put("methods", array);
                }
            }
            return errorJson("Class not found: " + className);
        } catch (Exception e) {
            return errorJson("Error fetching methods: " + e.getMessage());
        }
    }

    /**
     * Retrieves all field names defined in the specified Java class.
     *
     * @param params A JSON object containing the required parameter:
     *               - "class_name": The fully qualified name of the class.
     */
    private JSONObject handleGetFieldsOfClass(JSONObject params) {
        String className = params.optString("class_name", null);
        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    JSONArray array = new JSONArray();
                    for (JavaField field : cls.getFields()) {
                        array.put(field.getName());
                    }
                    return new JSONObject()
                            .put("class_name", className)
                            .put("fields", array);
                }
            }
            return errorJson("Class not found: " + className);
        } catch (Exception e) {
            return errorJson("Error fetching fields: " + e.getMessage());
        }
    }

    /**
     * Searches all decompiled classes for methods whose names match or contain the
     * provided string.
     *
     * @param params A JSON object containing the required parameter:
     *               - "method_name": A case-insensitive string to match method
     *               names.
     */
    private JSONObject handleSearchMethodByName(JSONObject params) {
        String methodName = params.optString("method_name", null);
        if (methodName == null || methodName.isEmpty()) {
            return errorJson("Missing required parameter 'method_name'");
        }

        try {
            JSONArray results = new JSONArray();
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                cls.decompile();
                for (JavaMethod method : cls.getMethods()) {
                    if (method.getName().toLowerCase().contains(methodName.toLowerCase())) {
                        JSONObject entry = new JSONObject()
                                .put("class_name", cls.getFullName())
                                .put("method_name", method.getName());
                        results.put(entry);
                    }
                }
            }

            JSONObject response = new JSONObject()
                    .put("query", methodName)
                    .put("results", results);

            if (results.isEmpty()) {
                response.put("message", "No methods found for: " + methodName);
            }

            return response;
        } catch (Exception e) {
            return errorJson("Error searching methods: " + e.getMessage());
        }
    }

    /**
     * Extracts the decompiled source code of a specific method within a given class.
     *
     * @param params A JSON object containing:
     *               - "class_name": The fully qualified name of the class.
     *               - "method_name": The name of the method to extract.
     */
    private JSONObject handleGetMethodCode(JSONObject params) {
        String className = params.optString("class_name", null);
        String methodName = params.optString("method_name", null);

        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }
        if (methodName == null || methodName.isEmpty()) {
            return errorJson("Missing required parameter 'method_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    cls.decompile();
                    for (JavaMethod method : cls.getMethods()) {
                        if (method.getName().equals(methodName)) {
                            String methodCode = method.getCodeStr();
                            if (methodCode == null || methodCode.trim().isEmpty()) {
                                String classCode = cls.getCode();
                                String extracted = MethodExtractor.extract(method, classCode);
                                if (extracted != null && !extracted.trim().isEmpty()) {
                                    return new JSONObject()
                                            .put("class_name", className)
                                            .put("method_name", methodName)
                                            .put("code", extracted);
                                }
                            }
                            return new JSONObject()
                                    .put("class_name", className)
                                    .put("method_name", methodName)
                                    .put("code", methodCode);
                        }
                    }
                    return errorJson("Method '" + methodName + "' not found in class '" + className + "'");
                }
            }
            return errorJson("Class '" + className + "' not found");
        } catch (Exception e) {
            return errorJson("Error fetching method code: " + e.getMessage());
        }
    }

    /**
     * Retrieves a list of all resource file names in the APK.
     */
    private JSONObject handleGetAllResourceFileNames(JSONObject params) {
        int offset = params.optInt("offset", 0);
        int limit = params.optInt("limit", 250);
        int maxLimit = 500;
        if (limit > maxLimit) {
            limit = maxLimit;
        }

        try {
            List<ResourceFile> resources = context.getDecompiler().getResources();
            int total = resources.size();

            JSONArray array = new JSONArray();
            for (int i = offset; i < Math.min(offset + limit, total); i++) {
                ResourceFile resFile = resources.get(i);
                array.put(resFile.getOriginalName());
            }

            return new JSONObject()
                    .put("total", total)
                    .put("offset", offset)
                    .put("limit", limit)
                    .put("resources", array);
        } catch (Exception e) {
            return errorJson("Error retrieving resource names: " + e.getMessage());
        }
    }

    /**
     * Retrieves the content of a specific resource file.
     *
     * @param params A JSON object containing:
     *               - "resource_name": The name of the resource to retrieve.
     */
    private JSONObject handleGetResourceFile(JSONObject params) {
        String resourceName = params.optString("resource_name", null);
        if (resourceName == null || resourceName.isEmpty()) {
            return errorJson("Missing required parameter 'resource_name'");
        }

        try {
            for (ResourceFile resFile : context.getDecompiler().getResources()) {
                if (resourceName.equals(resFile.getOriginalName())) {
                    ResContainer container = resFile.loadContent();
                    String contentStr = null;

                    if (container.getDataType() == ResContainer.DataType.TEXT || container.getDataType() == ResContainer.DataType.RES_TABLE) {
                        ICodeInfo content = container.getText();
                        if (content != null) {
                            contentStr = content.getCodeStr();
                        }
                    } else if (container.getDataType() == ResContainer.DataType.RES_LINK) {
                        try {
                            contentStr = ResourcesLoader.decodeStream(resFile, (size, is) -> {
                                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                                int nRead;
                                byte[] data = new byte[1024];
                                while ((nRead = is.read(data, 0, data.length)) != -1) {
                                    buffer.write(data, 0, nRead);
                                }
                                buffer.flush();
                                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                            });
                        } catch (Exception e) {
                            return errorJson("Error decoding resource stream: " + e.getMessage());
                        }
                    } else {
                        return errorJson("Unsupported resource type: " + container.getDataType());
                    }

                    if (contentStr != null) {
                        return new JSONObject()
                                .put("resource_name", resourceName)
                                .put("content", contentStr);
                    }
                    return errorJson("Resource content is empty.");
                }
            }
            return errorJson("Resource not found: " + resourceName);
        } catch (Exception e) {
            return errorJson("Error retrieving resource: " + e.getMessage());
        }
    }

    /**
     * Retrieves all references (xrefs) to a class.
     *
     * @param params A JSON object containing:
     *               - "class_name": The fully qualified name of the class.
     */
    private JSONObject handleGetClassXrefs(JSONObject params) {
        String className = params.optString("class_name", null);
        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    JSONArray array = new JSONArray();
                    for (JavaNode node : cls.getUseIn()) {
                        JSONObject usage = new JSONObject();
                        usage.put("name", node.getName());
                        usage.put("full_name", node.getFullName());
                        usage.put("type", node.getClass().getSimpleName());
                        array.put(usage);
                    }
                    return new JSONObject()
                            .put("class_name", className)
                            .put("xrefs", array);
                }
            }
            return errorJson("Class not found: " + className);
        } catch (Exception e) {
            return errorJson("Error fetching class xrefs: " + e.getMessage());
        }
    }

    /**
     * Retrieves all references (xrefs) to a method.
     *
     * @param params A JSON object containing:
     *               - "class_name": The fully qualified name of the class.
     *               - "method_name": The name of the method.
     */
    private JSONObject handleGetMethodXrefs(JSONObject params) {
        String className = params.optString("class_name", null);
        String methodName = params.optString("method_name", null);

        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }
        if (methodName == null || methodName.isEmpty()) {
            return errorJson("Missing required parameter 'method_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaMethod method : cls.getMethods()) {
                        if (method.getName().equals(methodName)) {
                            JSONArray array = new JSONArray();
                            for (JavaNode node : method.getUseIn()) {
                                JSONObject usage = new JSONObject();
                                usage.put("name", node.getName());
                                usage.put("full_name", node.getFullName());
                                usage.put("type", node.getClass().getSimpleName());
                                array.put(usage);
                            }
                            return new JSONObject()
                                    .put("class_name", className)
                                    .put("method_name", methodName)
                                    .put("xrefs", array);
                        }
                    }
                    return errorJson("Method '" + methodName + "' not found in class '" + className + "'");
                }
            }
            return errorJson("Class '" + className + "' not found");
        } catch (Exception e) {
            return errorJson("Error fetching method xrefs: " + e.getMessage());
        }
    }

    /**
     * Retrieves all references (xrefs) to a field.
     *
     * @param params A JSON object containing:
     *               - "class_name": The fully qualified name of the class.
     *               - "field_name": The name of the field.
     */
    private JSONObject handleGetFieldXrefs(JSONObject params) {
        String className = params.optString("class_name", null);
        String fieldName = params.optString("field_name", null);

        if (className == null || className.isEmpty()) {
            return errorJson("Missing required parameter 'class_name'");
        }
        if (fieldName == null || fieldName.isEmpty()) {
            return errorJson("Missing required parameter 'field_name'");
        }

        try {
            for (JavaClass cls : context.getDecompiler().getClasses()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaField field : cls.getFields()) {
                        if (field.getName().equals(fieldName)) {
                            JSONArray array = new JSONArray();
                            for (JavaNode node : field.getUseIn()) {
                                JSONObject usage = new JSONObject();
                                usage.put("name", node.getName());
                                usage.put("full_name", node.getFullName());
                                usage.put("type", node.getClass().getSimpleName());
                                array.put(usage);
                            }
                            return new JSONObject()
                                    .put("class_name", className)
                                    .put("field_name", fieldName)
                                    .put("xrefs", array);
                        }
                    }
                    return errorJson("Field '" + fieldName + "' not found in class '" + className + "'");
                }
            }
            return errorJson("Class '" + className + "' not found");
        } catch (Exception e) {
            return errorJson("Error fetching field xrefs: " + e.getMessage());
        }
    }

}
