package hexacloud.core.server;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CopyOnWriteArrayList;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.contracts.ServerOperations;
import hexacloud.core.server.route.RouteRule;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.server.route.ClusterController;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.infra.server.HttpTransport;
import hexacloud.infra.server.UndertowHttpTransport;
import hexacloud.infra.server.TcpProxyTransport;
import hexacloud.infra.server.TelnetTransport;
import hexacloud.infra.server.WsTransport;

public class ServerManager implements ServerOperations {

    private final List<Cluster> clusters;
    protected final ClusterEventBusManager eventManager;
    private final RouteRegistry routeRegistry;
    private final List<ServerTransport> activeTransports = new ArrayList<>();
    private final List<hexacloud.core.server.filter.HttpFilter> customFilters = new CopyOnWriteArrayList<>();
    private final List<RouteRule> routeRules = new CopyOnWriteArrayList<>();
    
    private boolean telnetEnabled = false;
    private boolean httpEnabled = false;
    private boolean wsEnabled = false;
    private boolean tcpProxyEnabled = false;
    private int port = 3000;
    private hexacloud.core.server.HttpEngine httpEngine = hexacloud.core.server.HttpEngine.JDK_DEFAULT;
    private hexacloud.core.server.PerformanceProfile performanceProfile = hexacloud.core.server.PerformanceProfile.STANDARD;
    private hexacloud.core.ports.SslContextPort sslContextPort;
    private int tcpSoTimeout = 30000;
    private boolean tcpKeepAlive = true;

    /**
     * Primary constructor accepting all clusters. Used by LocalGatewayAdapter.
     */
    public ServerManager(List<Cluster> clusters, ClusterEventBusManager eventManager) {
        this.clusters = clusters != null ? clusters : new ArrayList<>();
        this.eventManager = eventManager;
        this.routeRegistry = new RouteRegistry();
        for (Cluster cluster : this.clusters) {
            this.routeRegistry.registerController(new ClusterController(cluster));
        }
    }

    /**
     * Convenience constructor for single-cluster usage (backward compatible).
     */
    public ServerManager(Cluster cluster, ClusterEventBusManager eventManager) {
        this(cluster != null ? List.of(cluster) : List.of(), eventManager);
    }

    public ServerManager(int port, Cluster cluster, ClusterEventBusManager eventManager) {
        this(cluster != null ? List.of(cluster) : List.of(), eventManager);
        this.port = port;
    }

    private String getAppBasePackage() {
        String command = System.getProperty("sun.java.command");
        if (command == null || command.trim().isEmpty()) {
            return "";
        }
        String mainClass = command.split(" ")[0];
        int lastDot = mainClass.lastIndexOf('.');
        if (lastDot != -1) {
            return mainClass.substring(0, lastDot);
        }
        return "";
    }

    public void autoRegisterControllers(List<String> scanPackages) {
        List<String> packages = new ArrayList<>();
        if (scanPackages != null) {
            packages.addAll(scanPackages);
        }
        if (packages.isEmpty()) {
            String basePkg = getAppBasePackage();
            if (!basePkg.isEmpty()) {
                packages.add(basePkg);
            }
            packages.add("hexacloud");
        }

        for (String pkg : packages) {
            try {
                List<Class<? extends hexacloud.core.server.route.RouteController>> controllers =
                        hexacloud.core.utils.reflection.ClassScanner.scanPackage(pkg, hexacloud.core.server.route.RouteController.class);
                for (Class<? extends hexacloud.core.server.route.RouteController> clazz : controllers) {
                    if (clazz.getName().equals(ClusterController.class.getName())) {
                        continue;
                    }
                    
                    try {
                        hexacloud.core.server.route.RouteController controller = null;
                        Cluster firstCluster = clusters.isEmpty() ? null : clusters.get(0);
                        try {
                            if (firstCluster != null) {
                                java.lang.reflect.Constructor<? extends hexacloud.core.server.route.RouteController> ctor = clazz.getDeclaredConstructor(Cluster.class);
                                ctor.setAccessible(true);
                                controller = ctor.newInstance(firstCluster);
                            }
                        } catch (NoSuchMethodException e) {
                            java.lang.reflect.Constructor<? extends hexacloud.core.server.route.RouteController> ctor = clazz.getDeclaredConstructor();
                            ctor.setAccessible(true);
                            controller = ctor.newInstance();
                        }

                        if (controller != null) {
                            this.routeRegistry.registerController(controller);
                            for (Cluster c : this.clusters) {
                                c.getRouteRegistry().registerController(controller);
                            }
                            DebugUtils.info("RouteScanner: Auto-discovered and registered controller: " + clazz.getName());
                        }
                    } catch (Exception e) {
                        DebugUtils.error("RouteScanner: Failed to auto-instantiate controller " + clazz.getName(), e);
                    }
                }
            } catch (Exception e) {
                DebugUtils.error("RouteScanner: Failed to scan package " + pkg + " for RouteControllers", e);
            }
        }
    }

    public ServerManager enableTelnet(boolean enabled) {
        this.telnetEnabled = enabled;
        DebugUtils.info("ServerManager: Telnet transport " + (enabled ? "AUTHORIZED" : "DISABLED"));
        return this;
    }

    public ServerManager enableHttp(boolean enabled) {
        this.httpEnabled = enabled;
        DebugUtils.info("ServerManager: HTTP transport " + (enabled ? "AUTHORIZED" : "DISABLED"));
        return this;
    }

    public ServerManager enableWs(boolean enabled) {
        this.wsEnabled = enabled;
        DebugUtils.info("ServerManager: WebSocket transport " + (enabled ? "AUTHORIZED" : "DISABLED"));
        return this;
    }

    public ServerManager enableTcpProxy(boolean enabled) {
        this.tcpProxyEnabled = enabled;
        DebugUtils.info("ServerManager: TCP Proxy transport " + (enabled ? "AUTHORIZED" : "DISABLED"));
        return this;
    }

    public ServerManager tcpSoTimeout(int timeoutMs) {
        this.tcpSoTimeout = timeoutMs;
        return this;
    }

    public ServerManager tcpKeepAlive(boolean enabled) {
        this.tcpKeepAlive = enabled;
        return this;
    }

    public boolean isTelnetEnabled() {
        return telnetEnabled;
    }

    public boolean isHttpEnabled() {
        return httpEnabled;
    }

    public boolean isWsEnabled() {
        return wsEnabled;
    }

    public boolean isTcpProxyEnabled() {
        return tcpProxyEnabled;
    }

    public hexacloud.core.server.HttpEngine getHttpEngine() {
        return httpEngine;
    }

    public void setHttpEngine(hexacloud.core.server.HttpEngine httpEngine) {
        if (httpEngine != null) {
            this.httpEngine = httpEngine;
        }
    }

    public hexacloud.core.server.PerformanceProfile getPerformanceProfile() {
        return performanceProfile;
    }

    public void setPerformanceProfile(hexacloud.core.server.PerformanceProfile performanceProfile) {
        if (performanceProfile != null) {
            this.performanceProfile = performanceProfile;
        }
    }

    public hexacloud.core.ports.SslContextPort getSslContext() {
        return sslContextPort;
    }

    public void setSslContext(hexacloud.core.ports.SslContextPort sslContextPort) {
        this.sslContextPort = sslContextPort;
    }

    public ServerManager registerFilter(hexacloud.core.server.filter.HttpFilter filter) {
        this.customFilters.add(filter);
        return this;
    }

    public List<hexacloud.core.server.filter.HttpFilter> getCustomFilters() {
        return customFilters;
    }

    @Override
    public ServerManager listen(int port) {
        DebugUtils.info("ServerManager: Starting authorized protocol listeners on base port " + port + "...");
        
        // Stop any running transports before starting new ones
        stopTransports();

        if(telnetEnabled) {
            ServerTransport telnet = new TelnetTransport();
            telnet.listen(port, routeRegistry, clusters, customFilters);
            activeTransports.add(telnet);
        }
        
        if(httpEnabled) {
            ServerTransport http;
            if (httpEngine == hexacloud.core.server.HttpEngine.UNDERTOW) {
                UndertowHttpTransport undertowHttp = new UndertowHttpTransport();
                undertowHttp.setSslContext(this.sslContextPort);
                http = undertowHttp;
            } else {
                HttpTransport jdkHttp = new HttpTransport();
                jdkHttp.setSslContext(this.sslContextPort);
                http = jdkHttp;
            }
            http.setPerformanceProfile(this.performanceProfile);
            // HTTP runs on port + 1
            http.listen(port + 1, routeRegistry, clusters, customFilters);
            activeTransports.add(http);
        }
        
        if(wsEnabled) {
            ServerTransport ws = new WsTransport();
            // WS runs on port + 2
            ws.listen(port + 2, routeRegistry, clusters, customFilters);
            activeTransports.add(ws);
        }

        if(tcpProxyEnabled) {
            TcpProxyTransport tcpProxy = new TcpProxyTransport();
            tcpProxy.setSoTimeout(this.tcpSoTimeout);
            tcpProxy.setKeepAlive(this.tcpKeepAlive);
            // TCP Proxy runs on port + 3
            tcpProxy.listen(port + 3, routeRegistry, clusters, customFilters);
            activeTransports.add(tcpProxy);
        }
        
        if(activeTransports.isEmpty()) {
            DebugUtils.error("ServerManager: Cannot listen. No protocols were authorized! All are disabled.");
        }
        return this;
    }

    @Override
    public ServerManager listen() {
        listen(this.port);
        return this;
    }

    @Override
    public ServerManager stop() {
        stopTransports();
        return this;
    }

    private void stopTransports() {
        for(ServerTransport transport : activeTransports) {
            if(transport != null && transport.isRunning()) {
                transport.stop();
            }
        }
        activeTransports.clear();
    }

    /**
     * Register a custom route controller to expose additional business command endpoints.
     */
    public ServerManager registerRouteController(hexacloud.core.server.route.RouteController controller) {
        this.routeRegistry.registerController(controller);
        for (Cluster c : this.clusters) {
            c.getRouteRegistry().registerController(controller);
        }
        return this;
    }

    public void addRouteRule(RouteRule rule) {
        if (rule == null) {
            return;
        }
        this.routeRules.add(rule);
        this.routeRegistry.addRouteRule(rule);
    }

    public List<RouteRule> getRouteRules() {
        return routeRules;
    }
}
