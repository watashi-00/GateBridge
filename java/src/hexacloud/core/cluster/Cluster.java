package hexacloud.core.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.NodeUpdateResult;
import hexacloud.core.model.ServerNode;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.config.ClusterConfig;
import hexacloud.core.config.ClusterStatePersistence;
import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.cluster.event.ClusterEvent.NodeRegistered;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.server.route.ClusterController;

public class Cluster {

    public enum RoutingMode {
        TELEMETRY_ONLY,
        LOAD_BALANCER_ONLY,
        HYBRID
    }

    private final ConcurrentHashMap<String, ServerNode> cluster;
    private final ClusterEventBusManager eventManager;
    private final ClusterSecurityManager securityManager;
    private final RouteRegistry routeRegistry;
    private final ReentrantLock lock = new ReentrantLock();

    private String clusterName = ClusterConfig.DEFAULT_CLUSTER_NAME;
    private String clusterUri = ClusterConfig.DEFAULT_CLUSTER_URI;
    private List<ServerNode> tempCluster;
    private boolean batchMode = false;
    private volatile RoutingMode routingMode = RoutingMode.TELEMETRY_ONLY;

    private final java.util.Set<String> staticNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> persistedStaticNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> registeredStaticNodesThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean bootstrapMode = true;

    public java.util.Set<String> getStaticNodes() {
        return staticNodes;
    }

    public java.util.Set<String> getPersistedStaticNodes() {
        return persistedStaticNodes;
    }

    public Cluster() {
        this(ClusterConfig.DEFAULT_CLUSTER_NAME, null);
    }

    public Cluster(String clusterName) {
        this(clusterName, null);
    }

    public Cluster(String clusterName, ClusterEventBusManager eventManager) {
        this.cluster = new ConcurrentHashMap<>();
        this.clusterName = clusterName;
        this.eventManager = eventManager;
        this.securityManager = new ClusterSecurityManager(clusterName);
        this.routeRegistry = new RouteRegistry();
        this.routeRegistry.registerController(new ClusterController(this));
        ClusterRegistry.getInstance().registerCluster(this);
    }

    public RoutingMode getRoutingMode() {
        return routingMode;
    }

    public void setRoutingMode(RoutingMode routingMode) {
        this.routingMode = routingMode;
    }

    public RouteRegistry getRouteRegistry() {
        return routeRegistry;
    }

    public ClusterSecurityManager security() {
        return securityManager;
    }

    public void registerServer(ServerNode node) {
        lock.lock();
        try {
            if (node == null) return;
            if (!batchMode && this.tempCluster != null && !this.tempCluster.isEmpty()) {
                DebugUtils.error(this.clusterName, null, "Cannot register a new server while there are stopped servers in the cluster. Please register all stopped servers first.");
                return;
            }
            String host = validHost(node.host());
            if (host == null) return;

            String fullHost = host + ":" + node.port();
            if (bootstrapMode) {
                registeredStaticNodesThisRun.add(fullHost);
                staticNodes.add(fullHost);
                
                // If a state file was loaded, respect remote deletions of static nodes
                if (hexacloud.core.config.ClusterStatePersistence.isStateLoaded()) {
                    if (persistedStaticNodes.contains(fullHost) && !cluster.containsKey(fullHost)) {
                        return; // Ignore/Respect remote deletion
                    }
                }
            } else {
                // Dynamically registered at runtime
                node = node.withDynamic(true);
            }

            ServerNode validNode = new ServerNode(
                node.name(), host, node.port(), node.status(), node.isExternal(),
                node.pingProtocol(), node.pingPath(), node.pingHeaderName(), node.pingHeaderValue(), node.isDynamic(), node.telemetryOnly()
            );
            addClusterNode(validNode);
        } finally {
            lock.unlock();
        }
    }

    public void registerLoadedServer(ServerNode node) {
        lock.lock();
        try {
            if (node == null) return;
            String host = validHost(node.host());
            if (host == null) return;

            String fullHost = host + ":" + node.port();
            if (!node.isDynamic()) {
                staticNodes.add(fullHost);
                persistedStaticNodes.add(fullHost);
            }

            ServerNode validNode = new ServerNode(
                node.name(), host, node.port(), node.status(), node.isExternal(),
                node.pingProtocol(), node.pingPath(), node.pingHeaderName(), node.pingHeaderValue(), node.isDynamic(), node.telemetryOnly()
            );
            addClusterNode(validNode);
        } finally {
            lock.unlock();
        }
    }

    public void endBootstrapPhase() {
        lock.lock();
        try {
            this.bootstrapMode = false;
            if (hexacloud.core.config.ClusterStatePersistence.isStateLoaded()) {
                java.util.List<String> toPrune = new java.util.ArrayList<>();
                for (String fullHost : persistedStaticNodes) {
                    if (!registeredStaticNodesThisRun.contains(fullHost)) {
                        toPrune.add(fullHost);
                    }
                }
                for (String fullHost : toPrune) {
                    cluster.remove(fullHost);
                    staticNodes.remove(fullHost);
                    persistedStaticNodes.remove(fullHost);
                }
                if (!toPrune.isEmpty()) {
                    hexacloud.core.config.ClusterStatePersistence.saveState();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void registerServer(int port) {
        lock.lock();
        try {
            centralizedRegister(port, clusterUri, NodeStatus.OFFLINE, false);
        } finally {
            lock.unlock();
        }
    }

    public void registerServer(int port, NodeStatus status) {
        lock.lock();
        try {
            centralizedRegister(port, clusterUri, status, false);
        } finally {
            lock.unlock();
        }
    }

    public void deregisterServer(String fullHost) {
        lock.lock();
        try {
            DebugUtils.log("Deregistering server " + fullHost);
            removeClusterNode(fullHost);
        } finally {
            lock.unlock();
        }
    }

    public void deregisterLastServer() {
        lock.lock();
        try {
            DebugUtils.log("Deregistering last server in the cluster");
            removeClusterNode();
        } finally {
            lock.unlock();
        }
    }

    public void deregisterAllServers() {
        lock.lock();
        try {
            toggleAllServers(false);
        } finally {
            lock.unlock();
        }
    }

    public void registerAllServers() {
        lock.lock();
        try {
            toggleAllServers(true);
        } finally {
            lock.unlock();
        }
    }

    public void listClusterNodes() {
        lock.lock();
        try {
            for (ServerNode node : cluster.values()) {
                if (node != null) {
                    DebugUtils.log(node.toString());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public List<ServerNode> getCluster() {
        lock.lock();
        try {
            return new ArrayList<>(cluster.values());
        } finally {
            lock.unlock();
        }
    }

    public void updateStatusServer(String host, NodeStatus status) {
        lock.lock();
        try {
            if (!this.cluster.containsKey(host)) {
                DebugUtils.error(this.clusterName, host, "Cannot update status: Server host '" + host + "' is not registered in the cluster.");
                return;
            }
            this.cluster.computeIfPresent(host, (key, serverNode) -> serverNode.withStatus(status));
        } finally {
            lock.unlock();
        }
    }

    public NodeUpdateResult updateTelemetryServer(String host, int port, Double cpuUsage, Double ramUsage,
                                                 String runtime, Integer latencyMs, NodeStatus requestedStatus) {
        lock.lock();
        try {
            String targetKey = findNodeKey(host, port);
            if (targetKey == null) {
                DebugUtils.error(this.clusterName, host + ":" + port, "Cannot update telemetry: server node is not registered.");
                return null;
            }

            ServerNode current = this.cluster.get(targetKey);
            NodeStatus nextStatus = requestedStatus != null ? requestedStatus : NodeStatus.ONLINE;
            boolean statusChanged = current.status() != nextStatus;
            boolean telemetryUpdated = cpuUsage != null || ramUsage != null || runtime != null || latencyMs != null;

            ServerNode updated = statusChanged ? current.withStatus(nextStatus) : current;
            if (cpuUsage != null) updated.setCpuUsage(cpuUsage);
            if (ramUsage != null) updated.setRamUsage(ramUsage);
            if (runtime != null) updated.setRuntime(runtime);
            if (latencyMs != null) updated.setLatencyMs(latencyMs);

            this.cluster.put(targetKey, updated);
            if (!batchMode) {
                ClusterStatePersistence.saveState();
            }
            return new NodeUpdateResult(updated.getFullHost(), updated.pingProtocol().getFriendlyName(), statusChanged, telemetryUpdated);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Update an existing server node configuration in the registry.
     */
    public void updateServerNode(ServerNode updatedNode) {
        if (updatedNode == null) return;
        lock.lock();
        try {
            String key = updatedNode.getFullHost();
            if (this.cluster.containsKey(key)) {
                this.cluster.put(key, updatedNode);
                DebugUtils.log("Updated server node configuration: " + updatedNode);
                if (!batchMode) {
                    ClusterStatePersistence.saveState();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void toggleAllServers(boolean start) {
        lock.lock();
        try {
            this.batchMode = true;
            if (!start) {
                this.tempCluster = new ArrayList<>(cluster.values());
            }

            if (start && (this.tempCluster == null || this.tempCluster.isEmpty())) {
                DebugUtils.error(this.clusterName, null, "All servers are already running or no existing servers to start.");
                return;
            }

            if (!start && (cluster.isEmpty())) {
                DebugUtils.error(this.clusterName, null, "All servers are already stopped or no existing servers to stop.");
                return;
            }

            for (ServerNode node : start ? tempCluster : cluster.values()) {
                if (node != null) {
                    if (start) {
                        registerServer(node);
                    } else {
                        deregisterServer(node.getFullHost());
                    }
                }
            }

            if (start) {
                this.tempCluster = null;
            }
            this.batchMode = false;
            ClusterStatePersistence.saveState();
        } finally {
            this.batchMode = false;
            lock.unlock();
        }
    }

    private void centralizedRegister(int port, String host, NodeStatus status, boolean isExternal) {
        lock.lock();
        try {
            if (!batchMode && this.tempCluster != null && !this.tempCluster.isEmpty()) {
                DebugUtils.error(this.clusterName, null, "Cannot register a new server while there are stopped servers in the cluster. Please register all stopped servers first.");
                return;
            }
            DebugUtils.log("Registering server on host: " + host + ", port: " + port);
            host = validHost(host);
            addClusterNode(new ServerNode(host, port, status, isExternal));
        } finally {
            lock.unlock();
        }
    }

    private void addClusterNode(ServerNode node) {
        if (!validServer(node)) { return; }

        if (cluster.size() >= ClusterConfig.MAX_CLUSTER_SIZE) {
            DebugUtils.error(this.clusterName, null, "Cluster is full. Cannot add more nodes.");
            return;
        }

        cluster.put(node.getFullHost(), node);
        if (eventManager != null) {
            eventManager.dispatch(new NodeRegistered(node));
        }
        if (!batchMode) {
            ClusterStatePersistence.saveState();
        }
    }
    
    private void removeClusterNode() {
        if (cluster.isEmpty()) {
            DebugUtils.error(this.clusterName, null, "Cluster is empty. No nodes to remove.");
            return;
        }
        cluster.keySet().stream()
            .reduce((first, second) -> second)
            .ifPresent(lastKey -> {
                cluster.remove(lastKey);
                if (eventManager != null) {
                    eventManager.dispatch(new hexacloud.core.cluster.event.ClusterEvent.NodeDeregistered(lastKey));
                }
                if (!batchMode) {
                    ClusterStatePersistence.saveState();
                }
            });
    }

    private void removeClusterNode(String fullHost) {
        if (cluster.containsKey(fullHost)) {
            cluster.remove(fullHost);
            if (eventManager != null) {
                eventManager.dispatch(new hexacloud.core.cluster.event.ClusterEvent.NodeDeregistered(fullHost));
            }
            if (!batchMode) {
                ClusterStatePersistence.saveState();
            }
        }
    }

    private String validHost(String host) {
        if (host == null || host.isEmpty()) {
            DebugUtils.error(this.clusterName, null, "Invalid host: null or empty");
            return null;
        }

        if (!host.matches("^[a-zA-Z]+://.*")) {
            host = "http://" + host;
        }
        while (host.endsWith("/") || host.endsWith(":")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private boolean validServer(ServerNode node) {
        if (node == null) {
            DebugUtils.error(this.clusterName, null, "Invalid server node: null");
            return false;
        }
        if (node.host() == null || node.host().isEmpty()) {
            DebugUtils.error(this.clusterName, null, "Invalid server node: host is null or empty");
            return false;
        }
        if (node.port() < 0 || node.port() > 65535) {
            DebugUtils.error(this.clusterName, null, "Invalid server node: port is out of range");
            return false;
        }

        if (cluster.containsKey(node.getFullHost())) {
            DebugUtils.error(this.clusterName, null, "Invalid server node: node '" + node.getFullHost() + "' is already registered");
            return false;
        }

        return true;
    }

    private String findNodeKey(String host, int port) {
        if (host == null) return null;
        String normalizedTargetHost = host.replaceAll("^[a-zA-Z]+://", "");
        for (ServerNode node : cluster.values()) {
            if (node == null) continue;
            if (node.getHostWithoutProtocol().equals(normalizedTargetHost) && node.port() == port) {
                return node.getFullHost();
            }
        }
        return null;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getSecret() {
        return securityManager.getSecret();
    }

    public boolean isRequireToken() {
        return securityManager.isRequireToken();
    }

    public int getTimeoutMs() {
        return securityManager.getTimeoutMs();
    }

    public String getAllowedIps() {
        return securityManager.getAllowedIps();
    }

    public boolean authenticate(String token) {
        return securityManager.authenticate(token);
    }

    public boolean isIpAllowed(String ipAddress) {
        return securityManager.isIpAllowed(ipAddress);
    }

    public boolean checkRateLimit(String clientId) {
        return securityManager.checkRateLimit(clientId);
    }

    public int getRateLimitRequests() {
        return securityManager.getRateLimitRequests();
    }

    public int getRateLimitDurationSeconds() {
        return securityManager.getRateLimitDurationSeconds();
    }

    public void setTimeoutMs(int timeoutMs) {
        securityManager.setTimeoutMs(timeoutMs);
        ClusterStatePersistence.saveState();
    }

    public void setAllowedIps(String allowedIps) {
        securityManager.setAllowedIps(allowedIps);
        ClusterStatePersistence.saveState();
    }

    public void setRateLimit(int requests, int durationSeconds) {
        securityManager.setRateLimit(requests, durationSeconds);
        ClusterStatePersistence.saveState();
    }

    public void setSecret(String secret) {
        securityManager.setSecret(secret);
        ClusterStatePersistence.saveState();
    }

    public void setRequireToken(boolean requireToken) {
        securityManager.setRequireToken(requireToken);
        ClusterStatePersistence.saveState();
    }

    public void dispatchEvent(hexacloud.core.cluster.event.ClusterEvent event) {
        if (this.eventManager != null) {
            this.eventManager.dispatch(event);
        }
    }
}
