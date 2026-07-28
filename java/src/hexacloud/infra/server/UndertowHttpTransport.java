package hexacloud.infra.server;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.model.ServerNode;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.server.ServerTransport;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.server.route.RouteRule;
import hexacloud.core.server.filter.HttpFilter;
import hexacloud.core.server.filter.HttpRequest;
import hexacloud.core.server.filter.HttpResponse;
import hexacloud.core.server.filter.Order;
import hexacloud.core.server.filter.builtin.IpRestrictionFilter;
import hexacloud.core.server.filter.builtin.RateLimitFilter;
import hexacloud.core.server.filter.builtin.TokenAuthFilter;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;
import hexacloud.core.server.filter.HttpFilterChainImpl;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class UndertowHttpTransport implements ServerTransport {

    private Undertow server;
    private boolean running = false;
    private final ConcurrentHashMap<String, AtomicInteger> roundRobinIndices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RouteHandlerInfo> routeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpString> headerCache = new ConcurrentHashMap<>(128);
    private static final java.util.concurrent.ConcurrentLinkedQueue<byte[]> BUFFER_POOL = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final HttpString CORS_ALLOW_ORIGIN = HttpString.tryFromString("Access-Control-Allow-Origin");
    private static final HttpString CORS_ALLOW_METHODS = HttpString.tryFromString("Access-Control-Allow-Methods");
    private static final HttpString CORS_ALLOW_HEADERS = HttpString.tryFromString("Access-Control-Allow-Headers");
    private final ExecutorService virtualExecutor = ThreadManager.newVirtualThreadPool();
    private static final ThreadLocal<FastPrintWriter> FAST_WRITER = ThreadLocal.withInitial(FastPrintWriter::new);
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_2)
            .connectTimeout(java.time.Duration.ofMillis(5000))
            .executor(virtualExecutor)
            .build();

    private hexacloud.core.server.PerformanceProfile performanceProfile = hexacloud.core.server.PerformanceProfile.STANDARD;
    private final List<HttpFilter> activeFilters = new CopyOnWriteArrayList<>();
    private hexacloud.core.ports.SslContextPort sslContextPort;
    private List<Cluster> activeClusters = new java.util.ArrayList<>();

    private void rebuildFilters(List<Cluster> clusters, List<HttpFilter> customFilters) {
        activeFilters.clear();
        if (clusters != null) {
            for (Cluster cluster : clusters) {
                String allowedIps = cluster.getAllowedIps();
                if (allowedIps != null && !allowedIps.trim().isEmpty()) {
                    activeFilters.add(new IpRestrictionFilter(cluster));
                }
                if (cluster.getRateLimitRequests() > 0 && cluster.getRateLimitDurationSeconds() > 0) {
                    activeFilters.add(new RateLimitFilter(cluster));
                }
                if (cluster.isRequireToken()) {
                    activeFilters.add(new TokenAuthFilter(cluster));
                }
            }
        }
        activeFilters.addAll(customFilters);

        // Sort custom filters by @Order annotation value (if present)
        activeFilters.sort((f1, f2) -> {
            int o1 = f1.getClass().isAnnotationPresent(Order.class) ? f1.getClass().getAnnotation(Order.class).value() : 100;
            int o2 = f2.getClass().isAnnotationPresent(Order.class) ? f2.getClass().getAnnotation(Order.class).value() : 100;
            return Integer.compare(o1, o2);
        });
    }

    @Override
    public void setPerformanceProfile(hexacloud.core.server.PerformanceProfile profile) {
        if (profile != null) {
            this.performanceProfile = profile;
        }
    }

    public void setSslContext(hexacloud.core.ports.SslContextPort sslContextPort) {
        this.sslContextPort = sslContextPort;
    }

    @Override
    public void listen(int port, RouteRegistry registry, List<Cluster> clusters, List<HttpFilter> customFilters) {
        try {
            this.activeClusters = clusters != null ? clusters : new java.util.ArrayList<>();
            rebuildFilters(clusters, customFilters);
            // Configure Default ByteBuffer Pool to avoid pool starvation under high concurrency
            io.undertow.connector.ByteBufferPool bufferPool = new io.undertow.server.DefaultByteBufferPool(
                    true, 
                    16384, 
                    -1, 
                    24, 
                    0
            );
            Undertow.Builder builder = Undertow.builder()
                    .addHttpListener(port, "0.0.0.0")
                    .setByteBufferPool(bufferPool);
            
            if (sslContextPort != null && sslContextPort.isSslEnabled()) {
                builder.addHttpsListener(sslContextPort.getSslPort(), "0.0.0.0", sslContextPort.getSslContext());
            }
 
            if (performanceProfile == hexacloud.core.server.PerformanceProfile.MAX_PERFORMANCE) {
                // Maximized performance profile for container resource utilization
                builder.setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, true)
                        .setServerOption(UndertowOptions.BUFFER_PIPELINED_DATA, false)
                        .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
                        .setServerOption(UndertowOptions.ENABLE_STATISTICS, false)
                        .setSocketOption(org.xnio.Options.BACKLOG, 8192)
                        .setSocketOption(org.xnio.Options.TCP_NODELAY, true)
                        .setSocketOption(org.xnio.Options.REUSE_ADDRESSES, true)
                        .setIoThreads(Math.max(Runtime.getRuntime().availableProcessors(), 2))
                        .setWorkerThreads(Runtime.getRuntime().availableProcessors() * 8);
            } else {
                // Standard lightweight profile for normal operations
                builder.setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, true)
                        .setServerOption(UndertowOptions.BUFFER_PIPELINED_DATA, false)
                        .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
                        .setServerOption(UndertowOptions.ENABLE_STATISTICS, false)
                        .setSocketOption(org.xnio.Options.BACKLOG, 1024)
                        .setSocketOption(org.xnio.Options.TCP_NODELAY, true)
                        .setSocketOption(org.xnio.Options.REUSE_ADDRESSES, true)
                        .setIoThreads(Math.max(Runtime.getRuntime().availableProcessors() / 2, 2))
                        .setWorkerThreads(Runtime.getRuntime().availableProcessors() * 2);
            }

             server = builder.setHandler(new HttpHandler() {
                         @Override
                         public void handleRequest(HttpServerExchange exchange) throws Exception {
                             String fastPath = exchange.getRequestPath();
                             String fastMatchingPath = fastPath.startsWith("/v1/") ? fastPath.substring(3) : (fastPath.equals("/v1") ? "/" : fastPath);
                             
                             RouteHandlerInfo fastRouteInfo = null;
                             boolean isProxy = false;
                             if (fastMatchingPath.startsWith("/clusters/")) {
                                 isProxy = true;
                             } else if (registry.getRouteRulesList() != null && !registry.getRouteRulesList().isEmpty()) {
                                 fastRouteInfo = routeCache.computeIfAbsent(fastMatchingPath, path -> {
                                     String routeName = toRouteName(path);
                                     BiConsumer<String, PrintWriter> handler = registry.getRoutes().get(routeName);
                                     return new RouteHandlerInfo(handler, routeName);
                                 });
                                 isProxy = fastRouteInfo.handler == null;
                             }

                             // Fast-path for direct custom routes when no filters are active
                             if (!isProxy && activeFilters.isEmpty()) {
                                 if (fastRouteInfo == null) {
                                     fastRouteInfo = routeCache.computeIfAbsent(fastMatchingPath, path -> {
                                         String routeName = toRouteName(path);
                                         BiConsumer<String, PrintWriter> handler = registry.getRoutes().get(routeName);
                                         return new RouteHandlerInfo(handler, routeName);
                                     });
                                 }
                                 if (fastRouteInfo.handler != null) {
                                     processRequest(exchange, registry, activeClusters, customFilters);
                                     return;
                                 }
                             }

                             if (exchange.isInIoThread()) {
                                 java.util.concurrent.Executor executor = exchange.getConnection().getWorker();
                                 exchange.dispatch(executor, () -> {
                                     try {
                                         processRequest(exchange, registry, activeClusters, customFilters);
                                     } catch (Exception e) {
                                         handleError(exchange, e);
                                     }
                                 });
                                 return;
                             }
                             processRequest(exchange, registry, activeClusters, customFilters);
                         }
                     })
                     .build();

             server.start();
             running = true;
             DebugUtils.info("HTTP Transport (Undertow) successfully bound and listening on port " + port);
         } catch (Exception e) {
             DebugUtils.error("HTTP Transport (Undertow) failed to start on port " + port, e);
         }
     }

     private void processRequest(HttpServerExchange exchange, RouteRegistry registry, List<Cluster> clusters, List<HttpFilter> customFilters) {
         // Set CORS headers
         exchange.getResponseHeaders().put(CORS_ALLOW_ORIGIN, "*");
         exchange.getResponseHeaders().put(CORS_ALLOW_METHODS, "GET, POST, OPTIONS, PUT, DELETE");
         exchange.getResponseHeaders().put(CORS_ALLOW_HEADERS, "X-Cluster-Token, Content-Type, Authorization");

         if (io.undertow.util.Methods.OPTIONS.equals(exchange.getRequestMethod())) {
             exchange.setStatusCode(204);
             return;
         }

         try {
             String fastPath = exchange.getRequestPath();
             String fastMatchingPath = fastPath.startsWith("/v1/") ? fastPath.substring(3) : (fastPath.equals("/v1") ? "/" : fastPath);
             boolean isProxy = fastMatchingPath.startsWith("/clusters/") || (registry.getRouteRulesList() != null && !registry.getRouteRulesList().isEmpty());

             // Fast-path for direct custom routes when no filters are active
             if (!isProxy && activeFilters.isEmpty()) {
                 RouteHandlerInfo routeInfo = routeCache.computeIfAbsent(fastMatchingPath, path -> {
                     String routeName = toRouteName(path);
                     BiConsumer<String, PrintWriter> handler = registry.getRoutes().get(routeName);
                     return new RouteHandlerInfo(handler, routeName);
                 });

                 if (routeInfo.handler != null) {
                     if (routeInfo.routeName.equals("GET_NODES_JSON")) {
                         exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                     } else {
                         exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                     }
                     exchange.setStatusCode(200);
                     
                     FastPrintWriter out = FAST_WRITER.get();
                     out.reset();
                     String query = exchange.getQueryString();
                     String args = query != null ? query : "";
                     routeInfo.handler.accept(args, out);
                     
                     byte[] responseBytes = out.toBytes();
                     exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(responseBytes.length));
                     
                     exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(responseBytes));
                     return;
                 }
             }

             // 1. Wrap request and response
             UndertowHttpRequestImpl req = new UndertowHttpRequestImpl(exchange);
             UndertowHttpResponseImpl res = new UndertowHttpResponseImpl(exchange);

             // 2. Build active filter chain
             // Filters are pre-compiled in this.activeFilters list

             // 3. Define Route execution handler
             BiConsumer<HttpRequest, HttpResponse> routeHandler = (r, s) -> {
                 try {
                     String rawPath = r.getPath();
                     String matchingPath = rawPath;
                     if (matchingPath.startsWith("/v1/")) {
                         matchingPath = matchingPath.substring(3);
                     } else if (matchingPath.equals("/v1")) {
                         matchingPath = "/";
                     }

                     String targetClusterName = null;
                     String clusterSubpath = null;
                     boolean matchedRouteRule = false;

                     if (matchingPath.startsWith("/clusters/")) {
                         String pathWithoutClusters = matchingPath.substring("/clusters/".length());
                         int slashIdx = pathWithoutClusters.indexOf('/');
                         if (slashIdx != -1) {
                             targetClusterName = pathWithoutClusters.substring(0, slashIdx);
                             clusterSubpath = pathWithoutClusters.substring(slashIdx);
                         } else {
                             targetClusterName = pathWithoutClusters;
                             clusterSubpath = "/";
                         }
                     } else {
                         String routeName = toRouteName(matchingPath);
                         if (!registry.getRoutes().containsKey(routeName)) {
                             String requestHost = r.getHeader("Host");
                             RouteRule matchedRule = null;
                             List<RouteRule> rules = registry.getRouteRulesList();
                             if (rules != null) {
                                 for (RouteRule rule : rules) {
                                     if (rule.matches(requestHost, matchingPath)) {
                                         matchedRule = rule;
                                         break;
                                     }
                                 }
                             }
                             if (matchedRule != null) {
                                 targetClusterName = matchedRule.getClusterName();
                                 clusterSubpath = matchedRule.rewritePath(matchingPath);
                                 matchedRouteRule = true;
                             }
                         }
                     }

                     if (targetClusterName != null) {
                         Cluster targetCluster = ClusterRegistry.getInstance().getCluster(targetClusterName);
                         if (targetCluster == null) {
                             s.setStatus(404);
                             try (PrintWriter out = s.getWriter()) {
                                 out.print("404 Not Found - Unknown Cluster: " + targetClusterName);
                             }
                             return;
                         }

                         RouteRegistry targetRegistry = targetCluster.getRouteRegistry();
                         String routeName = clusterSubpath.length() > 1 ? clusterSubpath.substring(1).toUpperCase() : "";

                         // Check built-in cluster management routes
                         BiConsumer<String, PrintWriter> handler = targetRegistry.getRoutes().get(routeName);
                         if (handler != null) {
                             if (routeName.equals("GET_NODES_JSON")) {
                                 s.setContentType("application/json");
                             } else {
                                 s.setContentType("text/plain");
                             }
                             if (!s.isCommitted()) {
                                 s.setStatus(200);
                             }
                             try (PrintWriter out = s.getWriter()) {
                                 String query = r.getQuery();
                                 String args = query != null ? query : "";
                                 handler.accept(args, out);
                             }
                             return;
                         }

                         // Layer 7 Reverse Proxy Load Balancing
                         if (!matchedRouteRule && targetCluster.getRoutingMode() == Cluster.RoutingMode.TELEMETRY_ONLY) {
                             s.setStatus(403);
                             try (PrintWriter out = s.getWriter()) {
                                 out.print("403 Forbidden - Load balancing is disabled for cluster: " + targetClusterName);
                             }
                             return;
                         }

                         List<ServerNode> activeNodes = targetCluster.getCluster().stream()
                                 .filter(n -> n != null && n.status() == NodeStatus.ONLINE && !n.telemetryOnly())
                                 .collect(Collectors.toList());

                        if (activeNodes.isEmpty()) {
                            s.setStatus(503);
                            try (PrintWriter out = s.getWriter()) {
                                out.print("503 Service Unavailable - No active nodes in cluster: " + targetClusterName);
                            }
                            return;
                        }

                        // Round-Robin selection
                        AtomicInteger rrIdx = roundRobinIndices.computeIfAbsent(targetClusterName, k -> new AtomicInteger(0));
                        int selectedIndex = (rrIdx.getAndIncrement() & Integer.MAX_VALUE) % activeNodes.size();
                        ServerNode targetNode = activeNodes.get(selectedIndex);

                        // Forward request
                        String targetUrlStr = targetNode.getFullHost() + clusterSubpath;
                        String query = r.getQuery();
                        if (query != null && !query.isEmpty()) {
                            targetUrlStr += "?" + query;
                        }

                        long startTime = System.currentTimeMillis();
                        java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(targetUrlStr));

                        int timeout = targetCluster.getTimeoutMs() > 0 ? targetCluster.getTimeoutMs() : 5000;
                        reqBuilder.timeout(java.time.Duration.ofMillis(timeout));

                        // Copy request headers
                        Map<String, List<String>> reqHeaders = r.getHeaders();
                        if (reqHeaders != null) {
                            for (Map.Entry<String, List<String>> entry : reqHeaders.entrySet()) {
                                String hName = entry.getKey();
                                if (hName == null || hName.equalsIgnoreCase("Host") || hName.equalsIgnoreCase("Content-Length") || hName.equalsIgnoreCase("Connection") || hName.equalsIgnoreCase("Upgrade") || hName.equalsIgnoreCase("X-Forwarded-For") || hName.equalsIgnoreCase("X-Forwarded-Proto") || hName.equalsIgnoreCase("X-Forwarded-Host")) {
                                    continue;
                                }
                                for (String val : entry.getValue()) {
                                    reqBuilder.header(hName, val);
                                }
                            }
                        }

                        // Inject X-Forwarded-For
                        String clientIp = r.getClientIp();
                        String existingXff = r.getHeader("X-Forwarded-For");
                        String xff = (existingXff == null || existingXff.trim().isEmpty()) ? clientIp : (existingXff + ", " + clientIp);
                        reqBuilder.header("X-Forwarded-For", xff);

                        // Inject X-Forwarded-Proto
                        String proto = exchange.getRequestScheme().equalsIgnoreCase("https") ? "https" : "http";
                        String existingProto = r.getHeader("X-Forwarded-Proto");
                        if (existingProto != null && !existingProto.trim().isEmpty()) {
                            proto = existingProto;
                        }
                        reqBuilder.header("X-Forwarded-Proto", proto);

                        // Inject X-Forwarded-Host
                        String originalHost = r.getHeader("Host");
                        if (originalHost != null && !originalHost.trim().isEmpty()) {
                            reqBuilder.header("X-Forwarded-Host", originalHost);
                        }

                        // Forward body if present
                        String method = r.getMethod();
                        java.net.http.HttpRequest.BodyPublisher bodyPublisher;
                        boolean hasBody = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
                        if (hasBody) {
                            if (!exchange.isBlocking()) {
                                exchange.startBlocking();
                            }
                            bodyPublisher = java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> {
                                try {
                                    return exchange.getInputStream();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
                        } else {
                            bodyPublisher = java.net.http.HttpRequest.BodyPublishers.noBody();
                        }
                        reqBuilder.method(method, bodyPublisher);

                        java.net.http.HttpRequest proxyRequest = reqBuilder.build();

                        int respCode = 502;
                        java.net.http.HttpResponse<InputStream> proxyResponse = null;
                        try {
                            proxyResponse = httpClient.send(proxyRequest, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                            respCode = proxyResponse.statusCode();
                        } catch (Exception ex) {
                            respCode = 502;
                        }

                        long latencyMs = System.currentTimeMillis() - startTime;

                        // Passive Telemetry extraction
                        Double cpuVal = null;
                        Double ramVal = null;
                        if (proxyResponse != null) {
                            cpuVal = parseHeaderDouble(proxyResponse.headers(), "X-Telemetry-CPU", "X-Node-CPU");
                            ramVal = parseHeaderDouble(proxyResponse.headers(), "X-Telemetry-RAM", "X-Node-RAM");
                        }

                        targetCluster.updateTelemetryServer(targetNode.host(), targetNode.port(), cpuVal, ramVal, null, (int) latencyMs, null);
                        if (cpuVal != null) targetNode.setCpuUsage(cpuVal);
                        if (ramVal != null) targetNode.setRamUsage(ramVal);
                        targetNode.setLatencyMs((int) latencyMs);

                        // Copy response headers to client
                        if (proxyResponse != null) {
                            for (Map.Entry<String, List<String>> entry : proxyResponse.headers().map().entrySet()) {
                                String hName = entry.getKey();
                                if (hName == null || hName.equalsIgnoreCase("Transfer-Encoding") || hName.equalsIgnoreCase("Content-Length") || hName.equalsIgnoreCase("Connection")) {
                                    continue;
                                }
                                HttpString cachedHeader = headerCache.computeIfAbsent(hName, HttpString::tryFromString);
                                for (String val : entry.getValue()) {
                                    exchange.getResponseHeaders().add(cachedHeader, val);
                                }
                            }
                        }

                        // Send response
                        exchange.setStatusCode(respCode);
                        if (proxyResponse != null) {
                            if (!exchange.isBlocking()) {
                                exchange.startBlocking();
                            }
                            byte[] buf = BUFFER_POOL.poll();
                            if (buf == null) {
                                buf = new byte[8192];
                            }
                            try (InputStream in = proxyResponse.body();
                                 OutputStream os = exchange.getOutputStream()) {
                                int len;
                                while ((len = in.read(buf)) != -1) {
                                    os.write(buf, 0, len);
                                }
                                os.flush();
                            } finally {
                                BUFFER_POOL.offer(buf);
                            }
                        } else {
                            if (respCode == 502) {
                                byte[] respBytes = "502 Bad Gateway - Connection failed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                                if (!exchange.isBlocking()) {
                                    exchange.startBlocking();
                                }
                                try (OutputStream os = exchange.getOutputStream()) {
                                    os.write(respBytes);
                                    os.flush();
                                }
                            }
                        }

                    } else {
                        // Direct Custom Routes
                        final String finalLookupPath = matchingPath;
                        RouteHandlerInfo routeInfo = routeCache.computeIfAbsent(finalLookupPath, path -> {
                            String routeName = toRouteName(path);
                            BiConsumer<String, PrintWriter> handler = registry.getRoutes().get(routeName);
                            return new RouteHandlerInfo(handler, routeName);
                        });

                        if (routeInfo.handler != null) {
                            if (routeInfo.routeName.equals("GET_NODES_JSON")) {
                                s.setContentType("application/json");
                            } else {
                                s.setContentType("text/plain");
                            }
                            if (!s.isCommitted()) {
                                s.setStatus(200);
                            }
                            try (PrintWriter out = s.getWriter()) {
                                String query = r.getQuery();
                                String args = query != null ? query : "";
                                routeInfo.handler.accept(args, out);
                            }
                        } else {
                            s.setStatus(404);
                            try (PrintWriter out = s.getWriter()) {
                                out.print("404 Not Found - Unknown Route: " + matchingPath);
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            // 4. Run chain
            if (!activeFilters.isEmpty()) {
                HttpFilterChainImpl chain = new HttpFilterChainImpl(activeFilters, routeHandler);
                chain.doFilter(req, res);
            } else {
                routeHandler.accept(req, res);
            }
            res.flushBuffer();
            exchange.endExchange();

        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleError(HttpServerExchange exchange, Exception e) {
        System.err.println("UndertowHttpTransport: Exception caught in pipeline: " + e.getMessage());
        e.printStackTrace(System.err);
        DebugUtils.error("UndertowHttpTransport: Exception caught in pipeline: " + e.getMessage(), e);
        if (!exchange.getResponseHeaders().contains(Headers.CONTENT_TYPE)) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        }
        exchange.setStatusCode(500);
        exchange.getResponseSender().send("500 Internal Server Error - Execution failure: " + e.getMessage());
        exchange.endExchange();
    }

    private Double parseHeaderDouble(java.net.http.HttpHeaders headers, String... headerNames) {
        for (String hName : headerNames) {
            java.util.Optional<String> valOpt = headers.firstValue(hName);
            if (valOpt.isPresent()) {
                String val = valOpt.get();
                if (!val.trim().isEmpty()) {
                    try {
                        return Double.parseDouble(val.replace("%", "").trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return null;
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            running = false;
            virtualExecutor.shutdown();
            DebugUtils.log("HTTP Transport (Undertow) stopped.");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static String toRouteName(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path.substring(1).toUpperCase() : path.toUpperCase();
    }

    private static class RouteHandlerInfo {
        final BiConsumer<String, PrintWriter> handler;
        final String routeName;

        RouteHandlerInfo(BiConsumer<String, PrintWriter> handler, String routeName) {
            this.handler = handler;
            this.routeName = routeName;
        }
    }

    private static class FastPrintWriter extends java.io.PrintWriter {
        private static class StringBuilderWriter extends java.io.Writer {
            final StringBuilder sb = new StringBuilder(512);

            @Override
            public void write(char[] cbuf, int off, int len) {
                sb.append(cbuf, off, len);
            }

            @Override
            public void write(String str, int off, int len) {
                sb.append(str, off, off + len);
            }

            @Override
            public void write(int c) {
                sb.append((char)c);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}

            void reset() {
                sb.setLength(0);
            }

            byte[] toBytes() {
                return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        private final StringBuilderWriter sbw;

        public FastPrintWriter() {
            this(new StringBuilderWriter());
        }

        private FastPrintWriter(StringBuilderWriter sbw) {
            super(sbw);
            this.sbw = sbw;
        }

        public void reset() {
            sbw.reset();
            clearError();
        }

        public byte[] toBytes() {
            return sbw.toBytes();
        }
    }
}
