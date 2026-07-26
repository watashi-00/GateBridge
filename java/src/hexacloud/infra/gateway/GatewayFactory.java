package hexacloud.infra.gateway;

import hexacloud.core.ports.GatewayBuilderPort;

/**
 * Factory class for bootstrapping GateBridge gateways.
 * Instantiates the default LocalGatewayAdapter with the required configuration parameters.
 */
public class GatewayFactory {
    
    /**
     * Create a GateBridge Gateway instance with the specified gateway name.
     *
     * @param gatewayName the unique name of the gateway.
     * @return the GatewayBuilderPort implementation instance.
     */
    public static GatewayBuilderPort createGateway(String gatewayName) {
        return new LocalGatewayAdapter(gatewayName);
    }

    /**
     * Create a GateBridge Gateway instance and an initial active cluster.
     *
     * @param gatewayName the unique name of the gateway.
     * @param clusterName the initial cluster to create/select.
     * @return the GatewayBuilderPort implementation instance.
     */
    public static GatewayBuilderPort createGateway(String gatewayName, String clusterName) {
        return new LocalGatewayAdapter(gatewayName, clusterName);
    }

    /**
     * Create a GateBridge Gateway instance listening on a pre-configured Telnet port.
     *
     * @param gatewayName the unique name of the gateway.
     * @param port the Telnet server listening port.
     * @return the GatewayBuilderPort implementation instance.
     */
    public static GatewayBuilderPort createGateway(String gatewayName, int port) {
        return new LocalGatewayAdapter(gatewayName, port);
    }    
}
