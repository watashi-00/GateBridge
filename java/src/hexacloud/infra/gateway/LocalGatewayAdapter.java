package hexacloud.infra.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, ClusterManager> clusterManagers = new ConcurrentHashMap<>();
    private final ClusterEventBusManager clusterEventManager;
    private final ThreadPingScheduler schedulerPing;
    private String activeClusterName;
    private ServerManager serverManager;
    private int port = 3000;
    private boolean running = false;
    private String gatewayName;
    private boolean tcpProxyEnabled = false;
    private hexacloud.core.server.HttpEngine httpEngine = hexacloud.core.server.HttpEngine.JDK_DEFAULT;
    private hexacloud.core.server.PerformanceProfile performanceProfile = hexacloud.core.server.PerformanceProfile.STANDARD;
    private hexacloud.core.ports.SslContextPort sslContextPort;

    public LocalGatewayAdapter(String gatewayName) {
        DebugUtils.log("Creating LocalGatewayAdapter for gateway: " + gatewayName);
        this.clusterEventManager = new ClusterEventBusManager();
        autoRegisterEventListeners();
        
        // Load configurations state from file on startup
        ClusterStatePersistence.loadState();
        this.gatewayName = gatewayName;
        this.schedulerPing = new ThreadPingScheduler(gatewayName, this.clusterEventManager);
    }

    public LocalGatewayAdapter(String gatewayName, int port) {
        this(gatewayName);
        this.port = port;
    }

    public LocalGatewayAdapter(String gatewayName, String initialClusterName) {
        this(gatewayName);
        createCluster(initialClusterName);
    }

    @Override
    public NodeBuilderPort registerNode(String host, int port) {
        return new NodeBuilder(this, requireActiveCluster(), host, port);
    }

    @Override
    public NodeBuilderPort registerNode(String name, String host, int port) {
        return new NodeBuilder(this, requireActiveCluster(), name, host, port);
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
        schedulerPing.startPingScheduler(() -> this.getClusters().stream()
                .flatMap(cluster -> cluster.getCluster().stream())
                .collect(java.util.stream.Collectors.toList()));
        return this;
    }
    
    @Override
    public LocalGatewayAdapter startPingScheduler(int intervalInSeconds) {
        schedulerPing.setInterval(intervalInSeconds);
        schedulerPing.startPingScheduler(() -> this.getClusters().stream()
                .flatMap(cluster -> cluster.getCluster().stream())
                .collect(java.util.stream.Collectors.toList()));
        return this;
    }
    
	@Override
	public LocalGatewayAdapter registerAllServers() {
        requireActiveClusterManager().registerAllServers();
        return this;
	}

    @Override
    public LocalGatewayAdapter registerServer(int port) {
        requireActiveClusterManager().registerServer(port);
        return this;
    }

    @Override
    public LocalGatewayAdapter registerServer(int port, NodeStatus status) {
        requireActiveClusterManager().registerServer(port, status);
        return this;
    }

    @Override
    public LocalGatewayAdapter registerServer(ServerNode node) {
        requireActiveClusterManager().registerServer(node);
        return this;
    }

	@Override
	public LocalGatewayAdapter deregisterAllServers() {
        requireActiveClusterManager().deregisterAllServers();
        return this;
	}

	@Override
	public LocalGatewayAdapter deregisterServer(String fullHost) {
        requireActiveClusterManager().deregisterServer(fullHost);
        return this;
	}

	@Override
	public LocalGatewayAdapter deregisterLastServer() {
        requireActiveClusterManager().deregisterLastServer();
        return this;
	}

	@Override
	public LocalGatewayAdapter listClusterNodes() {
        requireActiveClusterManager().listClusterNodes();
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
            this.serverManager = new ServerManager(getClusters(), this.clusterEventManager);
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
        for (Cluster cluster : getClusters()) {
            cluster.endBootstrapPhase(); // Transition clusters to runtime
        }
        DebugUtils.log("LocalGatewayAdapter: Starting server listeners on port " + port);
        this.serverManager.listen(port);
        this.running = true;

        System.out.println("=================================================");
        System.out.println("GATEBRIDGE NODE STARTED: " + getGatewayName());
        System.out.println("Admin Port: " + getPort());
        System.out.println("Transports: HTTP=" + isHttpEnabled() + ", WS=" + isWsEnabled() + ", Telnet=" + isTelnetEnabled() + ", TCP Proxy=" + this.serverManager.isTcpProxyEnabled());
        System.out.println("Active Clusters:");
        for (Cluster cluster : getClusters()) {
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
        Cluster cluster = getCluster();
        return cluster != null ? cluster.getClusterName() : null;
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
    public LocalGatewayAdapter createCluster(String clusterName) {
        if (clusterName == null || clusterName.trim().isEmpty()) {
            throw new IllegalArgumentException("clusterName must not be empty");
        }
        String name = clusterName.trim();
        Cluster cluster = ClusterRegistry.getInstance().getCluster(name);
        if (cluster == null) {
            cluster = new Cluster(name, this.clusterEventManager);
        }
        final Cluster selectedCluster = cluster;
        this.clusterManagers.computeIfAbsent(name, ignored -> new ClusterManager(selectedCluster, this.clusterEventManager));
        this.activeClusterName = name;
        if (this.serverManager == null) {
            ensureServerManagerInitialized();
        }
        return this;
    }

    @Override
    public LocalGatewayAdapter useCluster(String clusterName) {
        Cluster cluster = clusterName != null ? ClusterRegistry.getInstance().getCluster(clusterName) : null;
        if (cluster == null) {
            throw new IllegalArgumentException("Unknown cluster: " + clusterName);
        }
        this.clusterManagers.computeIfAbsent(cluster.getClusterName(), ignored -> new ClusterManager(cluster, this.clusterEventManager));
        this.activeClusterName = cluster.getClusterName();
        return this;
    }

    @Override
    public Cluster getCluster(String clusterName) {
        if (clusterName == null) {
            return null;
        }
        ClusterManager manager = this.clusterManagers.get(clusterName);
        return manager != null ? manager.getCluster() : null;
    }

    @Override
    public List<Cluster> getClusters() {
        List<Cluster> clusters = new ArrayList<>();
        for (ClusterManager manager : this.clusterManagers.values()) {
            clusters.add(manager.getCluster());
        }
        return Collections.unmodifiableList(clusters);
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
        requireActiveCluster().setRateLimit(requests, durationSeconds);
        return this;
    }

    @Override
    public LocalGatewayAdapter requireToken(boolean requireToken, String secret) {
        Cluster cluster = requireActiveCluster();
        cluster.setRequireToken(requireToken);
        cluster.setSecret(secret);
        return this;
    }

    @Override
    public LocalGatewayAdapter allowedIps(String allowedIps) {
        requireActiveCluster().setAllowedIps(allowedIps);
        return this;
    }

    @Override
    public LocalGatewayAdapter timeout(int timeoutMs) {
        requireActiveCluster().setTimeoutMs(timeoutMs);
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
        if (this.activeClusterName == null) {
            return null;
        }
        return ClusterRegistry.getInstance().getCluster(this.activeClusterName);
    }

    @Override
    public LocalGatewayAdapter enableTcpProxy(boolean enabled) {
        this.tcpProxyEnabled = enabled;
        ensureServerManagerInitialized();
        this.serverManager.enableTcpProxy(enabled);
        return this;
    }

    private Cluster requireActiveCluster() {
        Cluster cluster = getCluster();
        if (cluster == null) {
            throw new IllegalStateException("No active cluster. Call createCluster(name) or useCluster(name) before configuring cluster nodes or policies.");
        }
        return cluster;
    }

    private ClusterManager requireActiveClusterManager() {
        Cluster cluster = requireActiveCluster();
        return this.clusterManagers.computeIfAbsent(cluster.getClusterName(), ignored -> new ClusterManager(cluster, this.clusterEventManager));
    }
}
