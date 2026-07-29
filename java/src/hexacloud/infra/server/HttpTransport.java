package hexacloud.infra.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.server.ServerTransport;
import hexacloud.core.server.filter.HttpFilter;
import hexacloud.core.server.filter.HttpFilterChainImpl;
import hexacloud.core.server.filter.HttpRequest;
import hexacloud.core.server.filter.HttpResponse;
import hexacloud.core.server.filter.Order;
import hexacloud.core.server.filter.builtin.IpRestrictionFilter;
import hexacloud.core.server.filter.builtin.RateLimitFilter;
import hexacloud.core.server.filter.builtin.TokenAuthFilter;
import hexacloud.core.server.filter.builtin.CorsFilter;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.server.route.RouteResolution;
import hexacloud.core.server.route.PathResolver;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;
import hexacloud.infra.server.filter.HttpRequestImpl;
import hexacloud.infra.server.filter.HttpResponseImpl;

/**
 * Concrete HTTP implementation of ServerTransport bound to a local port
 * and using virtual threads for routing and rate-limiting incoming traffic.
 * Supports Layer 7 Reverse-Proxy load balancing and passive telemetry extraction.
 */
public class HttpTransport implements ServerTransport {

    private HttpServer server;
    private boolean running = false;
    private final HttpErrorHandler errorHandler = new DefaultHttpErrorHandler();
    private final ReverseProxyService reverseProxyService = new ReverseProxyService(new hexacloud.core.utils.network.JdkHttpProxyClient(), errorHandler);

    private hexacloud.core.server.PerformanceProfile performanceProfile = hexacloud.core.server.PerformanceProfile.STANDARD;
    private final List<HttpFilter> activeFilters = new CopyOnWriteArrayList<>();
    private hexacloud.core.ports.SslContextPort sslContextPort;

    private void rebuildFilters(List<Cluster> clusters, List<HttpFilter> customFilters) {
        activeFilters.clear();
        
        // CORS filter is always the first filter in the chain
        activeFilters.add(new CorsFilter());

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
            rebuildFilters(clusters, customFilters);
            DebugUtils.info("HTTP Transport (JDK) starting on port " + port + " with profile: " + performanceProfile);
            if (sslContextPort != null && sslContextPort.isSslEnabled()) {
                com.sun.net.httpserver.HttpsServer httpsServer = com.sun.net.httpserver.HttpsServer.create(
                    new java.net.InetSocketAddress(port), 2048
                );
                httpsServer.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(sslContextPort.getSslContext()));
                server = httpsServer;
            } else {
                server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 2048);
            }
            server.setExecutor(ThreadManager.newVirtualThreadPool());
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    // CORS Configuration
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "X-Cluster-Token, Content-Type, Authorization");

                    if ("OPTIONS".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }

                    try {
                        String path = exchange.getRequestURI().getPath();
                        String matchingPath = path.startsWith("/v1/") ? path.substring(3) : (path.equals("/v1") ? "/" : path);

                        RouteResolution fastResolution = PathResolver.resolve(matchingPath, exchange.getRequestHeaders().getFirst("Host"), registry);
                        boolean canUseFastPath = fastResolution.isLocal() 
                                && registry.isRouteFastPath(fastResolution.localRouteName())
                                && (activeFilters.isEmpty() || (activeFilters.size() == 1 && activeFilters.get(0) instanceof CorsFilter));

                        if (canUseFastPath) {
                            BiConsumer<String, PrintWriter> handler = registry.getRoutes().get(fastResolution.localRouteName());
                            if (handler != null) {
                                if (fastResolution.localRouteName().equals("/V1/GET_NODES_JSON")) {
                                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                                } else {
                                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                                }
                                exchange.sendResponseHeaders(200, 0);
                                try (PrintWriter out = new PrintWriter(new java.io.BufferedWriter(new java.io.OutputStreamWriter(exchange.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8)))) {
                                    String query = exchange.getRequestURI().getQuery();
                                    String args = query != null ? query : "";
                                    handler.accept(args, out);
                                }
                                return;
                            }
                        }
                        HttpRequestImpl req = new HttpRequestImpl(exchange);
                        HttpResponseImpl res = new HttpResponseImpl(exchange);

                        RouteResolution resolution = PathResolver.resolve(req.getPath(), req.getHeader("Host"), registry);

                        // Inline default CorsFilter optimization
                        if (activeFilters.size() == 1 && activeFilters.get(0) instanceof CorsFilter) {
                            res.setHeader("Access-Control-Allow-Origin", "*");
                            res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
                            res.setHeader("Access-Control-Allow-Headers", "X-Cluster-Token, Content-Type, Authorization");

                            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                                res.setStatus(204);
                                return;
                            }

                            executeRoute(req, res, resolution, registry);
                            return;
                        }

                        BiConsumer<HttpRequest, HttpResponse> routeHandler = (r, s) -> {
                            try {
                                executeRoute(r, s, resolution, registry);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        };

                        HttpFilterChainImpl chain = new HttpFilterChainImpl(activeFilters, routeHandler);
                        chain.doFilter(req, res);

                    } catch (Exception e) {
                        DebugUtils.error("HttpTransport: Exception caught in filter chain pipeline: " + e.getMessage(), e);
                        try {
                            HttpResponseImpl res = new HttpResponseImpl(exchange);
                            errorHandler.handleException(res, e);
                        } catch (Exception ignored) {}
                    }
                }
            });

            server.start();
            running = true;
        } catch (Exception e) {
            DebugUtils.error("HttpTransport: Failed to start HTTP server on port " + port, e);
            throw new RuntimeException("HttpTransport start failed", e);
        }
    }

    private void executeRoute(HttpRequest r, HttpResponse s, RouteResolution resolution, RouteRegistry registry) throws Exception {
        if (resolution.isProxy()) {
            Cluster targetCluster = ClusterRegistry.getInstance().getCluster(resolution.targetClusterName());
            if (targetCluster == null) {
                errorHandler.handleStatus(s, 404, "Unknown Cluster: " + resolution.targetClusterName());
                return;
            }

            // Check if there is an internal cluster administration route
            RouteRegistry clusterRegistry = targetCluster.getRouteRegistry();
            String clusterRouteKey = resolution.resolveTargetRouteKey();
            if (clusterRegistry != null && clusterRouteKey != null && clusterRegistry.getRoutes().containsKey(clusterRouteKey)) {
                BiConsumer<String, PrintWriter> handler = clusterRegistry.getRoutes().get(clusterRouteKey);
                if (clusterRouteKey.equals("/V1/GET_NODES_JSON")) {
                    s.setContentType("application/json");
                } else {
                    s.setContentType("text/plain");
                }
                try (PrintWriter out = s.getWriter()) {
                    String query = r.getQuery();
                    String args = query != null ? query : "";
                    handler.accept(args, out);
                }
                return;
            }

            reverseProxyService.proxyRequest(r, s, targetCluster, resolution.targetSubpath(), targetCluster.getTimeoutMs(), resolution.matchedRouteRule());

        } else if (resolution.isLocal()) {
            BiConsumer<String, PrintWriter> handler = registry.getRoutes().get(resolution.localRouteName());
            if (resolution.localRouteName().equals("/V1/GET_NODES_JSON")) {
                s.setContentType("application/json");
            } else {
                s.setContentType("text/plain");
            }
            try (PrintWriter out = s.getWriter()) {
                String query = r.getQuery();
                String args = query != null ? query : "";
                handler.accept(args, out);
            }
        } else {
            errorHandler.handleStatus(s, 404, "Unknown Route: " + r.getPath());
        }
    }

    @Override
    public void stop() {
        if(server != null) {
            server.stop(0);
            running = false;
            DebugUtils.info("HTTP Transport stopped.");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
