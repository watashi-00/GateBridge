package hexacloud.infra.gateway;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterManager;
import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.ports.GatewayBuilderPort;
import hexacloud.core.ports.RunningGatewayPort;
import hexacloud.core.ports.NodeBuilderPort;
import hexacloud.core.server.ServerManager;
import hexacloud.infra.network.ThreadPingScheduler;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.config.ClusterStatePersistence;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.server.route.RouteRule;

class LocalGatewayAdapter implements GatewayBuilderPort, RunningGatewayPort {

    private final ClusterManager clusterManager;
    private final ClusterEventBusManager clusterEventManager;
    private final ThreadPingScheduler schedulerPing;
    private ServerManager serverManager;
    private int port = 3000;
    private boolean running = false;
    private String gatewayName;
    private boolean tcpProxyEnabled = false;
    private hexacloud.core.server.HttpEngine httpEngine = hexacloud.core.server.HttpEngine.JDK_DEFAULT;
    private hexacloud.core.server.PerformanceProfile performanceProfile = hexacloud.core.server.PerformanceProfile.STANDARD;
    private hexacloud.core.ports.SslContextPort sslContextPort;

    public LocalGatewayAdapter(String clusterName) {
        DebugUtils.log("Creating LocalGatewayAdapter for cluster: " + clusterName);
        this.clusterEventManager = new ClusterEventBusManager();
        autoRegisterEventListeners();
        
        // Load configurations state from file on startup
        ClusterStatePersistence.loadState();
        
        Cluster cluster = ClusterRegistry.getInstance().getCluster(clusterName);
        if (cluster == null) {
            cluster = new Cluster(clusterName, this.clusterEventManager);
        }
        
        this.clusterManager = new ClusterManager(cluster, this.clusterEventManager);
        this.schedulerPing = new ThreadPingScheduler(clusterName, this.clusterEventManager);
    }

    public LocalGatewayAdapter(String clusterName, int port) {
        DebugUtils.log("Creating LocalGatewayAdapter for cluster: " + clusterName + " with pre-configured server port " + port);
        this.clusterEventManager = new ClusterEventBusManager();
        autoRegisterEventListeners();
        
        // Load configurations state from file on startup
        ClusterStatePersistence.loadState();
        
        Cluster cluster = ClusterRegistry.getInstance().getCluster(clusterName);
        if (cluster == null) {
            cluster = new Cluster(clusterName, this.clusterEventManager);
        }
        
        this.clusterManager = new ClusterManager(cluster, this.clusterEventManager);
        this.schedulerPing = new ThreadPingScheduler(clusterName, this.clusterEventManager);
        this.port = port;
        this.serverManager = new ServerManager(port, this.clusterManager.getCluster(), this.clusterEventManager);
    }

    @Override
    public NodeBuilderPort registerNode(String host, int port) {
        return new NodeBuilder(this, this.clusterManager.getCluster(), host, port);
    }

    @Override
    public NodeBuilderPort registerNode(String name, String host, int port) {
        return new NodeBuilder(this, this.clusterManager.getCluster(), name, host, port);
    }

    @Override
    public LocalGatewayAdapter port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public LocalGatewayAdapter sslContext(hexacloud.core.ports.SslContextPort sslContextPort) {
        this.sslContextPort = sslContextPort;
        return this;
    }

    @Override
    public LocalGatewayAdapter pingInterval(int intervalInSeconds) {
        schedulerPing.setInterval(intervalInSeconds);
        return this;
    }

    @Override
    public LocalGatewayAdapter startPingScheduler() {
        schedulerPing.startPingScheduler(() -> this.clusterManager.getClusterList());
        return this;
    }
    
    @Override
    public LocalGatewayAdapter startPingScheduler(int intervalInSeconds) {
        schedulerPing.setInterval(intervalInSeconds);
        schedulerPing.startPingScheduler(() -> this.clusterManager.getClusterList());
        return this;
    }
    
	@Override
	public LocalGatewayAdapter registerAllServers() {
        clusterManager.registerAllServers();
        return this;
	}

    @Override
    public LocalGatewayAdapter registerServer(int port) {
        clusterManager.registerServer(port);
        return this;
    }

    @Override
    public LocalGatewayAdapter registerServer(int port, NodeStatus status) {
        clusterManager.registerServer(port, status);
        return this;
    }

    @Override
    public LocalGatewayAdapter registerServer(ServerNode node) {
        clusterManager.registerServer(node);
        return this;
    }

	@Override
	public LocalGatewayAdapter deregisterAllServers() {
        clusterManager.deregisterAllServers();
        return this;
	}

	@Override
	public LocalGatewayAdapter deregisterServer(String fullHost) {
        clusterManager.deregisterServer(fullHost);
        return this;
	}

	@Override
	public LocalGatewayAdapter deregisterLastServer() {
        clusterManager.deregisterLastServer();
        return this;
	}

	@Override
	public LocalGatewayAdapter listClusterNodes() {
        clusterManager.listClusterNodes();
        return this;
	}

	@Override
	public LocalGatewayAdapter setPingInterval(int pingInterval) {
        schedulerPing.setInterval(pingInterval);
        return this;
	}

    @Override
    public LocalGatewayAdapter stopPingScheduler() {
        schedulerPing.stopPingScheduler();
        return this;
    }

    private void ensureServerManagerInitialized() {
        if(this.serverManager == null) {
            this.serverManager = new ServerManager(this.clusterManager.getCluster(), this.clusterEventManager);
            this.serverManager.setHttpEngine(this.httpEngine);
            this.serverManager.setPerformanceProfile(this.performanceProfile);
            this.serverManager.enableTcpProxy(this.tcpProxyEnabled);
        }
    }

    private void autoRegisterEventListeners() {
        try {
            java.util.List<Class<?>> controllers = hexacloud.core.utils.common.PathUtils.scanClasspathForImplementations(hexacloud.core.event.EventController.class);
            for (Class<?> clazz : controllers) {
                try {
                    hexacloud.core.event.EventController listener = (hexacloud.core.event.EventController) clazz.getDeclaredConstructor().newInstance();
                    this.clusterEventManager.registerListener(listener);
                    DebugUtils.log("EventScanner: Auto-discovered and registered listener: " + clazz.getName());
                } catch (Exception e) {
                    DebugUtils.error("EventScanner: Failed to auto-instantiate listener " + clazz.getName(), e);
                }
            }
        } catch (Exception e) {
            DebugUtils.error("EventScanner: Failed to scan classpath for EventControllers", e);
        }
    }

    @Override
    public LocalGatewayAdapter enableTelnet(boolean enabled) {
        ensureServerManagerInitialized();
        this.serverManager.enableTelnet(enabled);
        return this;
    }

    @Override
    public LocalGatewayAdapter enableHttp(boolean enabled) {
        ensureServerManagerInitialized();
        this.serverManager.enableHttp(enabled);
        return this;
    }

    @Override
    public LocalGatewayAdapter enableWs(boolean enabled) {
        ensureServerManagerInitialized();
        this.serverManager.enableWs(enabled);
        return this;
    }

    @Override
    public LocalGatewayAdapter listen(int port) {
        this.port = port;
        ensureServerManagerInitialized();
        this.serverManager.setSslContext(this.sslContextPort);
        this.clusterManager.getCluster().endBootstrapPhase(); // Transition cluster to runtime
        DebugUtils.log("LocalGatewayAdapter: Starting server listeners on port " + port);
        this.serverManager.listen(port);
        this.running = true;

        System.out.println("=================================================");
        System.out.println("GATEBRIDGE NODE STARTED: " + getGatewayName());
        System.out.println("Admin Port: " + getPort());
        System.out.println("Transports: HTTP=" + isHttpEnabled() + ", WS=" + isWsEnabled() + ", Telnet=" + isTelnetEnabled() + ", TCP Proxy=" + this.serverManager.isTcpProxyEnabled());
        System.out.println("Active Clusters:");
        for (Cluster cluster : ClusterRegistry.getInstance().getClusters()) {
            System.out.println("   - Cluster: " + cluster.getClusterName() + " | RoutingMode: " + cluster.getRoutingMode() + " | Nodes: " + cluster.getCluster().size());
        }
        System.out.println("=================================================");

        return this;
    }

    @Override
    public LocalGatewayAdapter listen() {
        listen(this.port);
        return this;
    }

    @Override
    public LocalGatewayAdapter stop() {
        if (serverManager != null) {
            serverManager.stop();
        }
        schedulerPing.stopPingScheduler();
        this.running = false;
        return this;
    }

    @Override
    public String getClusterName() {
        return this.clusterManager.getCluster().getClusterName();
    }

    @Override
    public int getPort() {
        return this.port;
    }

    @Override
    public ClusterEventBusManager eventManager() {
        return this.clusterEventManager;
    }

    @Override
    public LocalGatewayAdapter registerController(hexacloud.core.server.route.RouteController controller) {
        ensureServerManagerInitialized();
        this.serverManager.registerRouteController(controller);
        return this;
    }

    @Override
    public LocalGatewayAdapter routeHost(String host, String pathPattern, String clusterName) {
        return routeHost(host, pathPattern, clusterName, null);
    }

    @Override
    public LocalGatewayAdapter routeHost(String host, String pathPattern, String clusterName, String targetPath) {
        ensureServerManagerInitialized();
        this.serverManager.addRouteRule(new RouteRule(host, pathPattern, clusterName, targetPath));
        Cluster targetCluster = ClusterRegistry.getInstance().getCluster(clusterName);
        if (targetCluster != null && targetCluster.getRoutingMode() == Cluster.RoutingMode.TELEMETRY_ONLY) {
            targetCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
        }
        return this;
    }

    @Override
    public LocalGatewayAdapter registerFilter(hexacloud.core.server.filter.HttpFilter filter) {
        ensureServerManagerInitialized();
        this.serverManager.registerFilter(filter);
        return this;
    }

    @Override
    public LocalGatewayAdapter rateLimit(int requests, int durationSeconds) {
        this.clusterManager.getCluster().setRateLimit(requests, durationSeconds);
        return this;
    }

    @Override
    public LocalGatewayAdapter requireToken(boolean requireToken, String secret) {
        this.clusterManager.getCluster().setRequireToken(requireToken);
        this.clusterManager.getCluster().setSecret(secret);
        return this;
    }

    @Override
    public LocalGatewayAdapter allowedIps(String allowedIps) {
        this.clusterManager.getCluster().setAllowedIps(allowedIps);
        return this;
    }

    @Override
    public LocalGatewayAdapter timeout(int timeoutMs) {
        this.clusterManager.getCluster().setTimeoutMs(timeoutMs);
        return this;
    }

    @Override
    public LocalGatewayAdapter httpEngine(hexacloud.core.server.HttpEngine engine) {
        if (engine != null) {
            this.httpEngine = engine;
            if (this.serverManager != null) {
                this.serverManager.setHttpEngine(engine);
            }
        }
        return this;
    }

    @Override
    public LocalGatewayAdapter performanceProfile(hexacloud.core.server.PerformanceProfile profile) {
        if (profile != null) {
            this.performanceProfile = profile;
            if (this.serverManager != null) {
                this.serverManager.setPerformanceProfile(profile);
            }
        }
        return this;
    }

    @Override
    public boolean isTelnetEnabled() {
        return serverManager != null && serverManager.isTelnetEnabled();
    }

    @Override
    public boolean isHttpEnabled() {
        return serverManager != null && serverManager.isHttpEnabled();
    }

    @Override
    public boolean isWsEnabled() {
        return serverManager != null && serverManager.isWsEnabled();
    }

    @Override
    public boolean isTcpProxyEnabled() {
        return serverManager != null && serverManager.isTcpProxyEnabled();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public LocalGatewayAdapter gatewayName(String name) {
        this.gatewayName = name;
        return this;
    }

    @Override
    public String getGatewayName() {
        return this.gatewayName != null ? this.gatewayName : "gw-" + port;
    }

    @Override
    public Cluster getCluster() {
        return this.clusterManager.getCluster();
    }

    @Override
    public LocalGatewayAdapter enableTcpProxy(boolean enabled) {
        this.tcpProxyEnabled = enabled;
        ensureServerManagerInitialized();
        this.serverManager.enableTcpProxy(enabled);
        return this;
    }
}
