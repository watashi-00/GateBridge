package hexacloud.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.ports.GatewayBuilderPort;
import hexacloud.core.ports.RunningGatewayPort;
import hexacloud.infra.gateway.GatewayFactory;

public class StateOrchestrationTest {

    private final String clusterName = "test-orchestration-cluster";
    private final String stateDirPath = "./.test_state";

    @BeforeEach
    public void setUp() {
        System.setProperty("hexacloud.state.dir", stateDirPath);
        deleteTestStateDir();
        ClusterRegistry.getInstance().clear();
    }

    @AfterEach
    public void tearDown() {
        deleteTestStateDir();
        System.clearProperty("hexacloud.state.dir");
    }

    private void deleteTestStateDir() {
        File dir = new File(stateDirPath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    @Test
    public void testRemoteDeletionAndBootstrapPersistence() {
        // 1. Initial run: bootstrap registers two static nodes A & B
        GatewayBuilderPort builder = GatewayFactory.createGateway("test-orchestration-gateway").createCluster(clusterName);
        builder.registerServer(new ServerNode("node-a", "http://localhost", 7001, NodeStatus.OFFLINE, false));
        builder.registerServer(new ServerNode("node-b", "http://localhost", 7002, NodeStatus.OFFLINE, false));
        
        // Starts listening (end of bootstrap)
        RunningGatewayPort gateway = builder.listen(7000);
        
        Cluster cluster = builder.getCluster();
        assertEquals(2, cluster.getCluster().size());
        
        // 2. User deletes node A remotely (simulated)
        gateway.deregisterServer("http://localhost:7001");
        assertEquals(1, cluster.getCluster().size());
        
        // Stops gateway
        gateway.stop();
        
        // 3. Restart: Reload state from disk and run bootstrap code registering A & B again
        GatewayBuilderPort restartedBuilder = GatewayFactory.createGateway("test-orchestration-gateway").createCluster(clusterName);
        // Bootstrap tries to register A & B again
        restartedBuilder.registerServer(new ServerNode("node-a", "http://localhost", 7001, NodeStatus.OFFLINE, false));
        restartedBuilder.registerServer(new ServerNode("node-b", "http://localhost", 7002, NodeStatus.OFFLINE, false));
        
        RunningGatewayPort restartedGateway = restartedBuilder.listen(7000);
        
        // Node A should NOT return because the remote deletion is respected
        Cluster restartedCluster = restartedBuilder.getCluster();
        assertEquals(1, restartedCluster.getCluster().size(), "Remote deletion of node-a must be persistent");
        assertNull(restartedCluster.getCluster().stream().filter(n -> n.port() == 7001).findFirst().orElse(null));
        assertNotNull(restartedCluster.getCluster().stream().filter(n -> n.port() == 7002).findFirst().orElse(null));
        
        restartedGateway.stop();
    }

    @Test
    public void testCodeDeletionPruning() {
        // 1. Initial run: bootstrap registers static nodes A & B
        GatewayBuilderPort builder = GatewayFactory.createGateway("test-orchestration-gateway").createCluster(clusterName);
        builder.registerServer(new ServerNode("node-a", "http://localhost", 7001, NodeStatus.OFFLINE, false));
        builder.registerServer(new ServerNode("node-b", "http://localhost", 7002, NodeStatus.OFFLINE, false));
        RunningGatewayPort gateway = builder.listen(7000);
        gateway.stop();

        // 2. Developer removes A from Main.java (bootstrap code)
        GatewayBuilderPort restartedBuilder = GatewayFactory.createGateway("test-orchestration-gateway").createCluster(clusterName);
        // Only B is registered by code this time
        restartedBuilder.registerServer(new ServerNode("node-b", "http://localhost", 7002, NodeStatus.OFFLINE, false));
        RunningGatewayPort restartedGateway = restartedBuilder.listen(7000);

        // A must be pruned because it was deleted from code
        Cluster restartedCluster = restartedBuilder.getCluster();
        assertEquals(1, restartedCluster.getCluster().size(), "Code-deleted node-a must be pruned");
        assertNull(restartedCluster.getCluster().stream().filter(n -> n.port() == 7001).findFirst().orElse(null));
        
        restartedGateway.stop();
    }

    @Test
    public void testCustomPersistenceAdapterInjection() {
        final boolean[] saveCalled = {false};
        final boolean[] loadCalled = {false};
        final boolean[] isStateLoadedCalled = {false};

        hexacloud.core.ports.ClusterPersistencePort customAdapter = new hexacloud.core.ports.ClusterPersistencePort() {
            @Override
            public void saveState() {
                saveCalled[0] = true;
            }

            @Override
            public void loadState() {
                loadCalled[0] = true;
            }

            @Override
            public boolean isStateLoaded() {
                isStateLoadedCalled[0] = true;
                return true;
            }
        };

        hexacloud.core.ports.ClusterPersistencePort originalAdapter = ClusterStatePersistence.getPersistenceAdapter();
        try {
            ClusterStatePersistence.setPersistenceAdapter(customAdapter);

            ClusterStatePersistence.saveState();
            ClusterStatePersistence.loadState();
            ClusterStatePersistence.isStateLoaded();

            assertTrue(saveCalled[0], "Custom saveState should be called");
            assertTrue(loadCalled[0], "Custom loadState should be called");
            assertTrue(isStateLoadedCalled[0], "Custom isStateLoaded should be called");
        } finally {
            ClusterStatePersistence.setPersistenceAdapter(originalAdapter);
        }
    }
}
