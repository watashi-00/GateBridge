package hexacloud.core.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import hexacloud.core.cluster.event.ClusterEvent;
import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.config.ClusterStatePersistence;
import hexacloud.core.event.EventFormat;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.PingProtocol;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.route.ClusterController;
import hexacloud.core.utils.common.Casts;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ExtendWith(MockitoExtension.class)
public class ClusterTest {

    private Cluster cluster;

    @Mock
    private ClusterEventBusManager eventManager;

    @BeforeEach
    public void setUp() {
        cluster = new Cluster("test-cluster-env", eventManager);
    }

    @Test
    public void testGetClusterName() {
        assertEquals("test-cluster-env", cluster.getClusterName());
        cluster.setClusterName("new-name");
        assertEquals("new-name", cluster.getClusterName());
    }

    @Test
    public void testRegisterServer() {
        ServerNode node = new ServerNode("127.0.0.1", 8080, NodeStatus.OFFLINE, false);
        cluster.registerServer(node);

        List<ServerNode> nodes = cluster.getCluster();
        assertFalse(nodes.isEmpty());
        assertEquals(1, nodes.size());
        assertEquals("http://127.0.0.1", nodes.get(0).host());
        assertEquals(8080, nodes.get(0).port());
    }

    @Test
    public void testDeregisterServer() {
        ServerNode node = new ServerNode("127.0.0.1", 9000, NodeStatus.OFFLINE, false);
        cluster.registerServer(node);
        assertEquals(1, cluster.getCluster().size());

        cluster.deregisterServer("127.0.0.1:9000");
        assertTrue(cluster.getCluster().isEmpty());
    }

    @Test
    public void testDuplicateRegistrationFails() {
        ServerNode node1 = new ServerNode("127.0.0.1", 8080, NodeStatus.OFFLINE, false);
        ServerNode node2 = new ServerNode("127.0.0.1", 8080, NodeStatus.OFFLINE, false);

        cluster.registerServer(node1);
        cluster.registerServer(node2); // Should fail silently due to duplicate validation

        assertEquals(1, cluster.getCluster().size());
    }

    @Test
    public void testBatchTogglingServers() {
        ServerNode node1 = new ServerNode("localhost", 3001, NodeStatus.OFFLINE, false);
        ServerNode node2 = new ServerNode("localhost", 3002, NodeStatus.OFFLINE, false);

        cluster.registerServer(node1);
        cluster.registerServer(node2);
        assertEquals(2, cluster.getCluster().size());

        // Stop all (toggle off)
        cluster.deregisterAllServers();
        assertTrue(cluster.getCluster().isEmpty());

        // Start all (toggle on)
        cluster.registerAllServers();
        assertEquals(2, cluster.getCluster().size());
    }

    @Test
    public void testSecurityDelegation() {
        cluster.setSecret("super-secret");
        assertTrue(cluster.isRequireToken());
        assertEquals("super-secret", cluster.getSecret());
        assertTrue(cluster.authenticate("super-secret"));
        assertFalse(cluster.authenticate("wrong-token"));

        cluster.setRequireToken(false);
        assertFalse(cluster.isRequireToken());
    }

    @Test
    public void testRateLimitingDelegation() {
        cluster.setRateLimit(200, 30);
        assertEquals(200, cluster.getRateLimitRequests());
        assertEquals(30, cluster.getRateLimitDurationSeconds());
    }

    @Test
    public void testTimeoutDelegation() {
        cluster.setTimeoutMs(4500);
        assertEquals(4500, cluster.getTimeoutMs());
    }

    @Test
    public void testTelemetryPushUpdatesStatusAndMetricsAtomically() {
        ServerNode node = new ServerNode("127.0.0.1", 7001, NodeStatus.OFFLINE, false);
        cluster.registerServer(node);

        ClusterController controller = new ClusterController(cluster);
        StringWriter response = new StringWriter();
        controller.telemetry("host=127.0.0.1&port=7001&status=ONLINE&cpu=33.5&ram=77.2&language=Java&latency=42", new PrintWriter(response, true));

        ServerNode updated = cluster.getCluster().stream()
            .filter(n -> n.getFullHost().equals("http://127.0.0.1:7001"))
            .findFirst()
            .orElseThrow();

        assertEquals(NodeStatus.ONLINE, updated.status());
        assertEquals(33.5, updated.cpuUsage());
        assertEquals(77.2, updated.ramUsage());
        assertEquals("Java", updated.runtime());
        assertEquals(42, updated.latencyMs());
        assertTrue(response.toString().contains("SUCCESS"));
    }

    @Test
    public void testTelemetryPushCanSubmitNodeEvent() {
        ServerNode node = new ServerNode("127.0.0.1", 7002, NodeStatus.OFFLINE, false,
            PingProtocol.GRPC, "/", null, null);
        cluster.registerServer(node);
        clearInvocations(eventManager);

        ClusterController controller = new ClusterController(cluster);
        StringWriter response = new StringWriter();
        controller.telemetry(
            "host=127.0.0.1&port=7002&event=cache.warmed&format=json&detail=products&token=secret-token",
            new PrintWriter(response, true)
        );

        verify(eventManager).dispatch(argThat(event ->
            Casts.test(event, ClusterEvent.NodeEventSubmitted.class, submitted ->
                submitted.host().equals("http://127.0.0.1:7002")
                && submitted.port() == 7002
                && submitted.protocol() == PingProtocol.GRPC
                && submitted.format() == EventFormat.JSON
                && submitted.event().equals("cache.warmed")
                && submitted.attributes().get("detail").equals("products")
                && !submitted.attributes().containsKey("token")
                && !submitted.attributes().containsKey("format")
            )
        ));
        assertTrue(response.toString().contains("SUCCESS"));
    }

    @Test
    public void testStatePersistenceDoesNotWriteSecrets() throws Exception {
        Path stateDir = Files.createTempDirectory("gatebridge-state-test");
        String previousStateDir = System.getProperty("hexacloud.state.dir");
        System.setProperty("hexacloud.state.dir", stateDir.toString());
        try {
            cluster.setSecret("super-secret");
            cluster.registerServer(new ServerNode("127.0.0.1", 7101, NodeStatus.OFFLINE, false,
                hexacloud.core.model.PingProtocol.HTTP, "/", "X-Test-Token", "node-secret"));

            ClusterStatePersistence.saveState();

            Path stateFile = stateDir.resolve("test-cluster-env-state.properties");
            String state = Files.readString(stateFile);
            assertFalse(state.contains("super-secret"));
            assertFalse(state.contains("node-secret"));
            assertFalse(state.contains(".secret="));
            assertFalse(state.contains("pingHeaderValue"));
        } finally {
            if (previousStateDir == null) {
                System.clearProperty("hexacloud.state.dir");
            } else {
                System.setProperty("hexacloud.state.dir", previousStateDir);
            }
        }
    }
}
