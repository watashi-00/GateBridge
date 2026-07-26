package hexacloud.core.ports;

import java.util.List;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;

/**
 * Interface representing the configuration and bootstrapping phase of the GateBridge gateway.
 * Methods return the builder instance to support fluent configuration chaining.
 */
public interface GatewayBuilderPort {

    /**
     * Start configuring a server node with custom health check parameters (ping path, token headers, etc.).
     */
    NodeBuilderPort registerNode(String host, int port);

    /**
     * Start configuring a named server node with custom health check parameters.
     */
    NodeBuilderPort registerNode(String name, String host, int port);

    /**
     * Set the main Telnet listening port.
     */
    GatewayBuilderPort port(int port);

    /**
     * Set the global check interval for the ping scheduler.
     */
    GatewayBuilderPort pingInterval(int intervalInSeconds);

    /**
     * Enable or disable the Telnet server transport interface.
     */
    GatewayBuilderPort enableTelnet(boolean enabled);

    /**
     * Enable or disable the HTTP REST API transport interface.
     */
    GatewayBuilderPort enableHttp(boolean enabled);

    /**
     * Enable or disable the WebSocket transport interface.
     */
    GatewayBuilderPort enableWs(boolean enabled);

    /**
     * Register a server node on a given port.
     */
    GatewayBuilderPort registerServer(int port);

    /**
     * Register a server node with a pre-configured status.
     */
    GatewayBuilderPort registerServer(int port, NodeStatus status);

    /**
     * Register a pre-constructed ServerNode instance.
     */
    GatewayBuilderPort registerServer(ServerNode serverNode);

    /**
     * Register all servers defined in the configurations.
     */
    GatewayBuilderPort registerAllServers();

    /**
     * Create or reuse a cluster and make it the active cluster for node registration.
     */
    GatewayBuilderPort createCluster(String clusterName);

    /**
     * Select an existing cluster as the active cluster for node registration.
     */
    GatewayBuilderPort useCluster(String clusterName);

    /**
     * Get a cluster by name, or null when it does not exist.
     */
    Cluster getCluster(String clusterName);

    /**
     * Get all clusters currently known by this gateway process.
     */
    List<Cluster> getClusters();

    /**
     * Register a custom route controller to expose additional business command endpoints.
     */
    GatewayBuilderPort registerController(hexacloud.core.server.route.RouteController controller);

    /**
     * Map a virtual host and path pattern directly to a target cluster.
     */
    GatewayBuilderPort routeHost(String host, String pathPattern, String clusterName);

    /**
     * Map a virtual host and path pattern to a target cluster with a backend path rewrite.
     */
    GatewayBuilderPort routeHost(String host, String pathPattern, String clusterName, String targetPath);

    /**
     * Register a custom HTTP filter to intercept incoming HTTP traffic.
     */
    GatewayBuilderPort registerFilter(hexacloud.core.server.filter.HttpFilter filter);

    /**
     * Set rate limit rules for the cluster gateway.
     */
    GatewayBuilderPort rateLimit(int requests, int durationSeconds);

    /**
     * Set security token validation settings.
     */
    GatewayBuilderPort requireToken(boolean requireToken, String secret);

    /**
     * Set allowed client IP whitelist (comma-separated).
     */
    GatewayBuilderPort allowedIps(String allowedIps);

    /**
     * Set connection/request timeout in milliseconds.
     */
    GatewayBuilderPort timeout(int timeoutMs);

    /**
     * Set the HTTP engine to use (JDK_DEFAULT or UNDERTOW).
     */
    GatewayBuilderPort httpEngine(hexacloud.core.server.HttpEngine engine);

    /**
     * Set the performance profile (STANDARD or MAX_PERFORMANCE).
     */
    GatewayBuilderPort performanceProfile(hexacloud.core.server.PerformanceProfile profile);

    /**
     * Set the custom name of this gateway.
     */
    GatewayBuilderPort gatewayName(String name);

    /**
     * Start all configured protocol listeners on the default port.
     * Transitions the gateway from the configuration builder phase to the running phase.
     */
    RunningGatewayPort listen();

    /**
     * Start all configured protocol listeners on the specified port.
     * Transitions the gateway from the configuration builder phase to the running phase.
     */
    RunningGatewayPort listen(int port);

    /**
     * Get the cluster model instance managed by this gateway.
     */
    Cluster getCluster();

    /**
     * Enable or disable Layer 4 TCP proxy load balancing.
     */
    GatewayBuilderPort enableTcpProxy(boolean enabled);

    /**
     * Configure an SSL/TLS context provider for HTTPS/TLS termination.
     */
    GatewayBuilderPort sslContext(hexacloud.core.ports.SslContextPort sslContextPort);
}
