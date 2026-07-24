package hexacloud.core.tui;

import java.util.ArrayList;
import java.util.List;

import hexacloud.core.event.TuiEvent;
import hexacloud.core.model.ServerNode;

/**
 * Encapsulates the UI state, navigation positions, viewports, and cached cluster/node configurations.
 */
public class TuiState {
    
    public int currentView = 0; // 0 = VIEW_DASHBOARD, 1 = VIEW_CLUSTER_DETAIL, 2 = VIEW_FULL_LOGS, 3 = VIEW_NODE_CONFIG
    public int activePanel = 0;  // 0 = PANEL_CLUSTERS, 1 = PANEL_SERVICES
    public int selectedClusterIndex = 0;
    public int selectedNodeIndex = 0;
    public int selectedLogIndex = 0;

    public int servicesViewportStart = 0;
    public int logViewportStart = 0;

    public List<String> clusterNames = new ArrayList<>();
    public String selectedClusterName = "";

    // Cached configurations for selected cluster
    public boolean targetRequireToken;
    public int targetTimeoutMs;
    public String targetAllowedIps = "";
    public int targetRateLimitRequests;
    public int targetRateLimitDurationSeconds;

    // Cached global configuration parameters
    public int globalPingInterval;

    public boolean running = true;
    public List<ServerNode> nodes = new ArrayList<>();

    public int selectedGatewayIndex = 0;
    
    public static class GatewayConfig {
        public String gatewayName = "";
        public String clusterName = "";
        public int port = 3000;
        public int pingInterval = 5;
        public boolean telnetEnabled = true;
        public boolean httpEnabled = true;
        public boolean wsEnabled = true;
        public boolean tcpProxyEnabled = false;
        public boolean running = false;
    }
    public final List<GatewayConfig> gateways = new ArrayList<>();

    public final List<TuiEvent> recentEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
}
