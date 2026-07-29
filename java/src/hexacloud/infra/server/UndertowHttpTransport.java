package hexacloud.infra.server;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.server.ServerTransport;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.server.route.RouteResolution;
import hexacloud.core.server.route.PathResolver;
import hexacloud.core.server.filter.HttpFilter;
import hexacloud.core.server.filter.HttpRequest;
import hexacloud.core.server.filter.HttpResponse;
import hexacloud.core.server.filter.Order;
import hexacloud.core.server.filter.builtin.IpRestrictionFilter;
import hexacloud.core.server.filter.builtin.RateLimitFilter;
import hexacloud.core.server.filter.builtin.TokenAuthFilter;
import hexacloud.core.server.filter.builtin.CorsFilter;
import hexacloud.core.server.filter.HttpFilterChainImpl;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class UndertowHttpTransport implements ServerTransport {

    private Undertow server;
    private boolean running = false;
    private java.util.concurrent.ExecutorService virtualExecutor;
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
            io.undertow.connector.ByteBufferPool bufferPool = new io.undertow.server.DefaultByteBufferPool(
                    false, 
                    8192, 
                    -1, 
                    2, 
                    0
            );
            Undertow.Builder builder = Undertow.builder()
                    .addHttpListener(port, "0.0.0.0")
                    .setByteBufferPool(bufferPool);
            
            if (sslContextPort != null && sslContextPort.isSslEnabled()) {
                builder.addHttpsListener(sslContextPort.getSslPort(), "0.0.0.0", sslContextPort.getSslContext());
            }
 
            if (performanceProfile == hexacloud.core.server.PerformanceProfile.MAX_PERFORMANCE) {
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

            virtualExecutor = ThreadManager.newVirtualThreadPool();

            builder.setHandler(new HttpHandler() {
                @Override
                public void handleRequest(HttpServerExchange exchange) throws Exception {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(virtualExecutor, () -> {
                            try {
                                processRequest(exchange, registry);
                            } catch (Exception e) {
                                handleError(exchange, e);
                            }
                        });
                        return;
                    }
                    processRequest(exchange, registry);
                }
            });

            server = builder.build();
            server.start();
            running = true;
            DebugUtils.info("HTTP Transport (Undertow) successfully bound and listening on port " + port);
        } catch (Exception e) {
            DebugUtils.error("HTTP Transport (Undertow) failed to start on port " + port, e);
        }
    }

    private void processRequest(HttpServerExchange exchange, RouteRegistry registry) {
        try {
            UndertowHttpRequestImpl req = new UndertowHttpRequestImpl(exchange);
            UndertowHttpResponseImpl res = new UndertowHttpResponseImpl(exchange);

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
            res.flushBuffer();
            exchange.endExchange();

        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleError(HttpServerExchange exchange, Exception e) {
        DebugUtils.error("UndertowHttpTransport: Exception caught in pipeline: " + e.getMessage(), e);
        try {
            UndertowHttpResponseImpl res = new UndertowHttpResponseImpl(exchange);
            errorHandler.handleException(res, e);
            res.flushBuffer();
            exchange.endExchange();
        } catch (Exception ignored) {}
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            running = false;
            if (virtualExecutor != null) {
                virtualExecutor.shutdown();
            }
            DebugUtils.info("HTTP Transport (Undertow) stopped.");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static final ThreadLocal<FastPrintWriter> FAST_WRITER = ThreadLocal.withInitial(FastPrintWriter::new);

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
