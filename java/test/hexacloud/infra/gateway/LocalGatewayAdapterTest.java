package hexacloud.infra.gateway;

import static org.junit.jupiter.api.Assertions.*;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.server.ServerManager;
import hexacloud.core.server.route.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

public class LocalGatewayAdapterTest {

    private LocalGatewayAdapter gateway;

    @BeforeEach
    public void setUp() {
        ClusterRegistry.getInstance().clear();
        gateway = (LocalGatewayAdapter) GatewayFactory.createGateway("test-gateway");
    }

    @Test
    public void testGatewayBuilderSetup() {
        gateway.port(5000)
               .gatewayName("custom-gateway")
               .pingInterval(10)
               .enableHttp(true)
               .enableTelnet(true)
               .enableWs(true);

        assertEquals("custom-gateway", gateway.getGatewayName());
        assertEquals(5000, gateway.getPort());
        assertFalse(gateway.isRunning()); // Should be stopped by default
    }

    @Test
    public void testGatewayDefaultNameFallback() {
        gateway.port(6000);
        assertEquals("test-gateway", gateway.getGatewayName());
        assertNull(gateway.getCluster());
        assertTrue(gateway.getClusters().isEmpty());
    }

    @Test
    public void testCreateMultipleClustersAndSelectActiveCluster() {
        gateway.createCluster("auth-cluster")
               .createCluster("api-cluster")
               .useCluster("auth-cluster");

        assertEquals("auth-cluster", gateway.getCluster().getClusterName());
        assertEquals(2, gateway.getClusters().size());
        assertNotNull(gateway.getCluster("api-cluster"));
    }

    @Test
    public void testRouteHostRegistersRuleInTransportRegistry() throws Exception {
        gateway.createCluster("test-cluster");
        gateway.routeHost("localhost", "/auth/**", "test-cluster");

        ServerManager serverManager = readField(gateway, "serverManager", ServerManager.class);
        RouteRegistry routeRegistry = readField(serverManager, "routeRegistry", RouteRegistry.class);

        assertEquals(1, routeRegistry.getRouteRulesList().size());
        assertTrue(routeRegistry.getRouteRulesList().get(0).matches("localhost:3001", "/auth/a"));
        assertEquals("test-cluster", routeRegistry.getRouteRulesList().get(0).getClusterName());
        assertNull(routeRegistry.getRouteRulesList().get(0).getTargetPath());
        assertEquals(Cluster.RoutingMode.HYBRID, gateway.getCluster().getRoutingMode());
    }

    @Test
    public void testRouteHostOverloadRegistersTargetPath() throws Exception {
        gateway.createCluster("test-cluster");
        gateway.routeHost("localhost", "/auth/**", "test-cluster", "/api");

        ServerManager serverManager = readField(gateway, "serverManager", ServerManager.class);
        RouteRegistry routeRegistry = readField(serverManager, "routeRegistry", RouteRegistry.class);

        assertEquals(1, routeRegistry.getRouteRulesList().size());
        assertEquals("/api", routeRegistry.getRouteRulesList().get(0).getTargetPath());
        assertEquals("/api/a", routeRegistry.getRouteRulesList().get(0).rewritePath("/auth/a"));
    }

    private static <T> T readField(Object target, String fieldName, Class<T> fieldType) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldType.cast(field.get(target));
    }
}
