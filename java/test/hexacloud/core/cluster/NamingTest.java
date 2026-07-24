package hexacloud.core.cluster;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import hexacloud.core.model.ServerNode;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.PingProtocol;

public class NamingTest {
    @Test
    public void testNodeAndClusterNaming() {
        ServerNode node = new ServerNode("node-1", "http://localhost", 3001, NodeStatus.OFFLINE, false, PingProtocol.HTTP, "/health", null, null);
        assertEquals("node-1", node.name());
        Cluster cluster = new Cluster("cluster-1", null);
        assertEquals("cluster-1", cluster.getClusterName());
        
        cluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
        assertEquals(Cluster.RoutingMode.HYBRID, cluster.getRoutingMode());
    }
}
