package hexacloud.core.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.server.route.RouteRegistry;

import hexacloud.core.server.filter.HttpFilter;
import java.util.List;

public interface ServerTransport {
    void listen(int port, RouteRegistry registry, Cluster cluster, List<HttpFilter> customFilters);
    void stop();
    boolean isRunning();
    default void setPerformanceProfile(PerformanceProfile profile) {}
}
