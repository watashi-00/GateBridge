package hexacloud.core.cluster;

import java.util.List;

import hexacloud.core.cluster.event.ClusterEvent;
import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.cluster.event.ClusterListener;
import hexacloud.core.cluster.event.ClusterEvent.NodeStatusChanged;
import hexacloud.core.contracts.ClusterOperations;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.utils.common.Casts;
import hexacloud.core.utils.common.DebugUtils;

public class ClusterManager implements ClusterListener, ClusterOperations {

    private final Cluster cluster;
    protected final ClusterEventBusManager eventManager;

    public ClusterManager(Cluster cluster, ClusterEventBusManager eventManager) {
        this.cluster = cluster;
        this.eventManager = eventManager;

        this.eventManager.sub(NodeStatusChanged.class, this);
    }

    public List<ServerNode> getClusterList() {
        return this.cluster.getCluster();
    }
    
    public Cluster getCluster() {
        return this.cluster;
    }
    
    @Override
    public ClusterManager registerAllServers() {
        this.cluster.registerAllServers();
        return this;
    }

    @Override
    public ClusterManager registerServer(int port) {
        this.cluster.registerServer(port);
        return this;
    }

    @Override
    public ClusterManager registerServer(ServerNode node) {
        this.cluster.registerServer(node);
        return this;
    }

    @Override
    public ClusterManager registerServer(int port, NodeStatus status) {
        this.cluster.registerServer(port, status);
        return this;
    }

    @Override
	public ClusterManager deregisterAllServers() {
        this.cluster.deregisterAllServers();
        return this;
	}

    @Override
	public ClusterManager deregisterServer(String fullHost) {
        this.cluster.deregisterServer(fullHost);
        return this;
	}

    @Override
	public ClusterManager deregisterLastServer() {
        this.cluster.deregisterLastServer();
        return this;
	}

    @Override
	public ClusterManager listClusterNodes() {
        this.cluster.listClusterNodes();
        return this;
	}

    @Override
    public void onClusterEvent(ClusterEvent event) {
        Casts.as(event, NodeStatusChanged.class).ifPresent(statusEvent -> {
            if (statusEvent != null) {
                DebugUtils.info(
                    this.cluster.getClusterName(),
                    statusEvent.nodeId(),
                    "Node status changed: " + statusEvent.nodeId() + " -> " + statusEvent.status()
                );
                // statusEvent.host() == node.getFullHost(), which is the actual map key.
                // statusEvent.nodeId() is the node name and must NOT be used as a lookup key.
                this.cluster.updateStatusServer(statusEvent.host(), statusEvent.status());
            }
        });
    }
}
