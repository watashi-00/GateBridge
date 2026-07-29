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
                    try {
                        HttpRequestImpl req = new HttpRequestImpl(exchange);
                        HttpResponseImpl res = new HttpResponseImpl(exchange);

                        RouteResolution resolution = PathResolver.resolve(req.getPath(), req.getHeader("Host"), registry);

                        BiConsumer<HttpRequest, HttpResponse> routeHandler = (r, s) -> {
                            try {
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
