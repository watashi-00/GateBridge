package hexacloud.infra.network;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import hexacloud.core.cluster.event.ClusterEventBusManager;
import hexacloud.core.cluster.event.ClusterEvent.NodeStatusChanged;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.PingResult;
import hexacloud.core.model.ServerNode;
import hexacloud.core.ports.PingClientPort;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;
import hexacloud.core.config.ClusterConfig;

/**
 * Thread ping scheduler that schedules health check tasks at fixed rates 
 * using decoupled PingClientPort adapters.
 */
public class ThreadPingScheduler {

    private ScheduledExecutorService scheduler;
    private int interval = ClusterConfig.DEFAULT_PING_INTERVAL_SECONDS;

    private final String clusterName;
    private final PingClientPort pingClient;
    private final ClusterEventBusManager eventManager;

    public ThreadPingScheduler(String clusterName, ClusterEventBusManager eventManager) {
        this.clusterName = clusterName;
        this.eventManager = eventManager;
        this.pingClient = new MultiProtocolPingAdapter();
    }
    
    public void startPingScheduler(Supplier<List<ServerNode>> clusterSupplier) {

        if(scheduler == null || scheduler.isShutdown()) {
            scheduler = ThreadManager.newScheduledThreadPool(ClusterConfig.SCHEDULER_THREAD_POOL_SIZE, "ping-scheduler-");
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    for(ServerNode node : clusterSupplier.get()) {
                        if(node != null) {
                            pingClusterNode(node);
                        }
                    }
                } catch (Exception e) {
                    DebugUtils.error(clusterName, null, "Unexpected error in ping scheduler execution", e);
                }
            }, 0, this.interval, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    public void stopPingScheduler() {
        if(scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if(!scheduler.awaitTermination(ClusterConfig.AWAIT_TERMINATION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch(InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void setInterval(int intervalInSeconds) {
        this.interval = intervalInSeconds;
    }

    private void pingClusterNode(ServerNode node) {
        if (!node.pingEnabled()) {
            return;
        }
        CompletableFuture<PingResult> response = pingClient.fetchPingAsync(clusterName, node);

        response.thenAccept(result -> {
            NodeStatus status = result.status();
            boolean statusChanged = node.status() != status;
            if (statusChanged) {
                eventManager.dispatch(new NodeStatusChanged(node.getFullHost(), status, node.getId()));
                DebugUtils.info("Node " + node.getFullHost() + " status updated to " + status + " (" + node.getId() + ")");
            }
            
            if (result.hasTelemetry()){
                eventManager.dispatch(new hexacloud.core.cluster.event.ClusterEvent.NodeTelemetryUpdated(node.getFullHost(), node.getId()));
            }
        });
    }
}
