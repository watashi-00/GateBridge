package hexacloud.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class ServerNodeTest {

    @Test
    public void testGetHostWithoutProtocol() {
        ServerNode node1 = new ServerNode("http://localhost", 8080, NodeStatus.OFFLINE, false);
        assertEquals("localhost", node1.getHostWithoutProtocol());

        ServerNode node2 = new ServerNode("ws://192.168.1.5", 3000, NodeStatus.OFFLINE, false);
        assertEquals("192.168.1.5", node2.getHostWithoutProtocol());

        ServerNode node3 = new ServerNode("grpc://my-service.domain.com", 50051, NodeStatus.OFFLINE, false);
        assertEquals("my-service.domain.com", node3.getHostWithoutProtocol());

        // Test fallback if no scheme exists
        ServerNode node4 = new ServerNode("localhost", 9000, NodeStatus.OFFLINE, false);
        assertEquals("localhost", node4.getHostWithoutProtocol());
    }

    @Test
    public void testGetFullHost() {
        ServerNode node = new ServerNode("http://localhost", 8080, NodeStatus.OFFLINE, false);
        assertEquals("http://localhost:8080", node.getFullHost());
    }

    @Test
    public void testWithStatus() {
        ServerNode node = new ServerNode("http://localhost", 8080, NodeStatus.OFFLINE, false);
        ServerNode updatedNode = node.withStatus(NodeStatus.ONLINE);

        assertEquals(NodeStatus.ONLINE, updatedNode.status());
        assertEquals(NodeStatus.OFFLINE, node.status()); // Immutable check
    }

    @org.junit.jupiter.api.Test
    public void testIsDynamicDefaultAndModifier() {
        ServerNode node = new ServerNode("node-test", "http://localhost", 9091, NodeStatus.OFFLINE, false);
        org.junit.jupiter.api.Assertions.assertFalse(node.isDynamic(), "Should default to false");
        
        ServerNode dynamicNode = node.withDynamic(true);
        org.junit.jupiter.api.Assertions.assertTrue(dynamicNode.isDynamic(), "Modified node should be dynamic");
        org.junit.jupiter.api.Assertions.assertEquals("node-test", dynamicNode.name());
    }

    @Test
    public void testTelemetryOnlyProperty() {
        ServerNode node = new ServerNode("node1", "localhost", 8080, NodeStatus.OFFLINE, false,
            PingProtocol.HTTP, "/", null, null, false, true);
        assertTrue(node.telemetryOnly());
    }

    @Test
    public void testTelemetryOnlyDefault() {
        ServerNode node = new ServerNode("node1", "localhost", 8080, NodeStatus.OFFLINE, false);
        assertFalse(node.telemetryOnly());
    }

    @Test
    public void testNodeBuilderTelemetryOnly() {
        hexacloud.core.cluster.Cluster cluster = new hexacloud.core.cluster.Cluster("test-cluster");
        hexacloud.infra.gateway.NodeBuilder builder = new hexacloud.infra.gateway.NodeBuilder(null, cluster, "telemetry-node", "localhost", 9090);
        builder.telemetryOnly(true).register();

        ServerNode node = cluster.getCluster().get(0);
        assertTrue(node.telemetryOnly());
    }

    @Test
    public void testClusterStatePersistenceTelemetryOnly() {
        hexacloud.core.cluster.Cluster cluster = new hexacloud.core.cluster.Cluster("persistence-cluster");
        ServerNode telemetryNode = new ServerNode("node1", "http://localhost", 9095, NodeStatus.OFFLINE, false,
            PingProtocol.HTTP, "/", null, null, false, true);
        cluster.registerServer(telemetryNode);

        hexacloud.core.config.ClusterStatePersistence.saveState();
        
        // Clear registry to simulate reload
        hexacloud.core.cluster.ClusterRegistry.getInstance().clear();
        hexacloud.core.config.ClusterStatePersistence.loadState();

        hexacloud.core.cluster.Cluster reloadedCluster = hexacloud.core.cluster.ClusterRegistry.getInstance().getCluster("persistence-cluster");
        assertNotNull(reloadedCluster);
        assertEquals(1, reloadedCluster.getCluster().size());
        assertTrue(reloadedCluster.getCluster().get(0).telemetryOnly());
    }
}




