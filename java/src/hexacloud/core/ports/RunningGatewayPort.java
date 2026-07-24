package hexacloud.core.ports;

import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.model.ServerNode;

/**
 * Interface representing a running GateBridge gateway.
 * Provides APIs for runtime lifecycle control, scheduler management, and dynamic node registration.
 */
public interface RunningGatewayPort {

    /**
     * Start the ping health scheduler with the default check interval.
     */
    RunningGatewayPort startPingScheduler();

    /**
     * Start the ping health scheduler with a custom check interval.
     */
    RunningGatewayPort startPingScheduler(int intervalInSeconds);

    /**
     * Stop the ping health scheduler.
     */
    RunningGatewayPort stopPingScheduler();

    /**
     * Set the global check interval for the ping scheduler.
     */
    RunningGatewayPort setPingInterval(int intervalInSeconds);

    /**
     * Register a new node dynamically while the gateway is running.
     */
    RunningGatewayPort registerServer(ServerNode serverNode);

    /**
     * Remove a node from the cluster dynamically by its full host name (e.g. "http://localhost:3001").
     */
    RunningGatewayPort deregisterServer(String fullHost);

    /**
     * Remove the last registered node from the cluster dynamically.
     */
    RunningGatewayPort deregisterLastServer();

    /**
     * Remove all nodes from the cluster.
     */
    RunningGatewayPort deregisterAllServers();

    /**
     * List all current cluster nodes to standard output/logs.
     */
    RunningGatewayPort listClusterNodes();

    /**
     * Stop all gateway protocol listeners and the ping health scheduler.
     */
    RunningGatewayPort stop();

    /**
     * Get the name of the cluster managed by this gateway.
     */
    String getClusterName();

    /**
     * Get the base port number this gateway is listening on.
     */
    int getPort();

    /**
     * Get the custom name of this gateway.
     */
    String getGatewayName();

    /**
     * Access the event manager to subscribe to cluster node changes or connection pings.
     */
    ClusterEventBusManager eventManager();

    boolean isTelnetEnabled();
    boolean isHttpEnabled();
    boolean isWsEnabled();
    boolean isTcpProxyEnabled();
    boolean isRunning();
}
