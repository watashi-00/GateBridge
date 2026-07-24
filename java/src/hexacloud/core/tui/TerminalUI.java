package hexacloud.core.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.event.TuiEvent;
import hexacloud.core.utils.common.Casts;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.terminal.NativeTerminal;
import hexacloud.core.ports.RunningGatewayPort;

/**
 * Main coordinator for the DevOps TUI console dashboard.
 * Delegates rendering, keyboard processing, and text dialogues to sub-components.
 */
public class TerminalUI implements hexacloud.core.ports.TerminalUiPort {

    private String displayName;
    private final TuiState state = new TuiState();
    
    private final TuiRenderer renderer;
    private final TuiKeyHandler keyHandler;
    private final TuiPrompts prompts;

    // Feature Flags
    private boolean readOnly = false;
    private boolean gatewayManagementEnabled = true;
    private boolean clusterManagementEnabled = true;
    private boolean nodeManagementEnabled = true;
    private boolean nodeConfigurationEnabled = true;
    private boolean tokenManagementEnabled = true;
    private boolean isToggleMode = false;

    private static final Map<String, RunningGatewayPort> activeGateways = new ConcurrentHashMap<>();
    private static final Map<String, Integer> gatewayPorts = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore redrawSemaphore = new java.util.concurrent.Semaphore(0);
    private volatile boolean bypassDebounce = false;

    public void triggerRedraw() {
        triggerRedraw(false);
    }

    public void triggerRedraw(boolean immediate) {
        if (immediate) {
            bypassDebounce = true;
        }
        redrawSemaphore.release();
    }

    /**
     * Start the Terminal UI client with the default settings.
     */
    public static void startTerminal(String displayName) {
        new TerminalUI(displayName).run();
    }

    /**
     * Start the Terminal UI client seeding it with an already started RunningGatewayPort instance.
     */
    public static void startTerminal(String displayName, RunningGatewayPort gateway) {
        if (gateway != null) {
            activeGateways.put(gateway.getClusterName(), gateway);
            gatewayPorts.put(gateway.getClusterName(), gateway.getPort());
        }
        new TerminalUI(displayName).run();
    }

    /**
     * Initialize TerminalUI.
     */
    public TerminalUI(String displayName) {
        this.displayName = displayName != null ? displayName : "GateBridge Control Plane";
        this.renderer = new TuiRenderer(this);
        this.keyHandler = new TuiKeyHandler(this);
        this.prompts = new TuiPrompts(this);
    }

    public TuiState state() {
        return state;
    }

    public TuiRenderer renderer() {
        return renderer;
    }

    public TuiKeyHandler keyHandler() {
        return keyHandler;
    }

    public TuiPrompts prompts() {
        return prompts;
    }

    public Map<String, RunningGatewayPort> activeGateways() {
        return activeGateways;
    }

    public String displayName() {
        return displayName;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public boolean gatewayManagementEnabled() {
        return gatewayManagementEnabled;
    }

    public boolean clusterManagementEnabled() {
        return clusterManagementEnabled;
    }

    public boolean nodeManagementEnabled() {
        return nodeManagementEnabled;
    }

    public boolean nodeConfigurationEnabled() {
        return nodeConfigurationEnabled;
    }

    @Override
    public boolean tokenManagementEnabled() {
        return tokenManagementEnabled;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort gatewayManagementEnabled(boolean enabled) {
        this.gatewayManagementEnabled = enabled;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort clusterManagementEnabled(boolean enabled) {
        this.clusterManagementEnabled = enabled;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort nodeManagementEnabled(boolean enabled) {
        this.nodeManagementEnabled = enabled;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort nodeConfigurationEnabled(boolean enabled) {
        this.nodeConfigurationEnabled = enabled;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort tokenManagementEnabled(boolean enabled) {
        this.tokenManagementEnabled = enabled;
        return this;
    }

    @Override
    public hexacloud.core.ports.TerminalUiPort seedGateway(RunningGatewayPort gateway) {
        if (gateway != null) {
            activeGateways.put(gateway.getClusterName(), gateway);
            gatewayPorts.put(gateway.getClusterName(), gateway.getPort());
        }
        return this;
    }

    @Override
    public void start() {
        this.run();
    }

    @Override
    public void startToggleMode() {
        this.isToggleMode = true;
        System.out.println("\n>>> GateBridge Gateway is running in background.");
        System.out.println(">>> Standard logging is active. Press ENTER to open the DevOps TUI Dashboard anytime.");

        hexacloud.core.utils.concurrent.ThreadManager.startVirtual("TuiToggleListener", () -> {
            boolean toggleActive = false;
            while (true) {
                if (!toggleActive) {
                    try {
                        int key = NativeTerminal.readKey();
                        if (key == 10 || key == 13 || key == 'm' || key == 'M') { // Enter or 'm' key
                            toggleActive = true;
                            
                            // This blocks until the TUI exits (state.running = false)
                            this.run();
                            
                            toggleActive = false;
                            System.out.println("\n>>> DevOps TUI detached. Gateway is still running in background.");
                            System.out.println(">>> Press ENTER to open the DevOps TUI Dashboard again.");
                        }
                    } catch (Exception e) {
                        // Ignore JNI read errors
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    public boolean isGatewayActive(String clusterName) {
        return activeGateways.containsKey(clusterName);
    }

    /**
     * Launch the TUI loop.
     */
    public void run() {
        state.running = true;
        DebugUtils.setTuiModeActive(true);

        NativeTerminal.initTerminal();
        registerShutdownHook();

        hexacloud.core.event.EventListener<hexacloud.core.event.Event> interceptor = null;
        try {
            interceptor = registerEventBusInterceptors();
            initializeStateAndSubscriptions();
            startInputReader();

            // Initial render
            renderer.draw();

            executeRedrawLoop();
        } catch (Exception e) {
            NativeTerminal.resetTerminal();
            e.printStackTrace();
        } finally {
            cleanup(interceptor);
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(NativeTerminal::resetTerminal));
    }

    private hexacloud.core.event.EventListener<hexacloud.core.event.Event> registerEventBusInterceptors() {
        hexacloud.core.event.EventListener<hexacloud.core.event.Event> interceptor = event -> {
            String name = event.getClass().getSimpleName();

            String detail = Casts.<String>matchValue(event)
                .when(hexacloud.core.cluster.event.ClusterEvent.NodeStatusChanged.class,
                    e -> e.host().replaceAll("^[a-zA-Z]+://", "") + " -> " + e.status())
                .when(hexacloud.core.cluster.event.ClusterEvent.NodeTelemetryUpdated.class,
                    e -> e.host().replaceAll("^[a-zA-Z]+://", "") + " updated")
                .when(hexacloud.core.cluster.event.ClusterEvent.NodeEventSubmitted.class,
                    e -> e.event() + " [" + e.protocol() + "/" + e.format() + "] from "
                        + e.host().replaceAll("^[a-zA-Z]+://", ""))
                .when(hexacloud.core.cluster.event.ClusterEvent.NodeRegistered.class,
                    e -> e.node().getFullHost().replaceAll("^[a-zA-Z]+://", ""))
                .when(hexacloud.core.cluster.event.ClusterEvent.NodeDeregistered.class,
                    e -> e.host().replaceAll("^[a-zA-Z]+://", ""))
                .when(hexacloud.core.cluster.event.ClusterEvent.ClusterRegistered.class,
                    hexacloud.core.cluster.event.ClusterEvent.ClusterRegistered::clusterName)
                .otherwise(e -> {
                    try {
                        java.lang.reflect.Method m = e.getClass().getMethod("message");
                        return (String) m.invoke(e);
                    } catch (Exception ex) {
                        try {
                            java.lang.reflect.Method m = e.getClass().getMethod("getMessage");
                            return (String) m.invoke(e);
                        } catch (Exception ex2) {
                            return e.toString();
                        }
                    }
                });

            state.recentEvents.add(0, new TuiEvent(name, detail, System.currentTimeMillis()));
            while (state.recentEvents.size() > 8) {
                state.recentEvents.remove(state.recentEvents.size() - 1);
            }
            triggerRedraw();
        };

        hexacloud.core.event.EventBusManager.getGlobal().addInterceptor(interceptor);
        return interceptor;
    }

    private void initializeStateAndSubscriptions() {
        // Load persisted state configuration files from disk
        hexacloud.core.config.ClusterStatePersistence.loadState();

        // Direct logs notifications to trigger TUI redraws
        DebugUtils.setLogListener(() -> triggerRedraw(false));

        // Fetch initial configuration & clusters list
        fetchClusterNames();
        fetchGlobalConfig();
        if (!state.clusterNames.isEmpty()) {
            state.selectedClusterName = state.clusterNames.get(0);
            fetchNodeStatus();
            fetchClusterConfig(state.selectedClusterName);
        }

        // Subscribe to Global Event Bus for event-driven redraw triggers
        hexacloud.core.event.EventBusManager.getGlobal().sub(hexacloud.core.cluster.event.ClusterEvent.NodeStatusChanged.class, event -> triggerRedraw());
        hexacloud.core.event.EventBusManager.getGlobal().sub(hexacloud.core.cluster.event.ClusterEvent.NodeTelemetryUpdated.class, event -> triggerRedraw());
        hexacloud.core.event.EventBusManager.getGlobal().sub(hexacloud.core.cluster.event.ClusterEvent.NodeEventSubmitted.class, event -> triggerRedraw());
        hexacloud.core.event.EventBusManager.getGlobal().sub(hexacloud.core.cluster.event.ClusterEvent.NodeRegistered.class, event -> triggerRedraw());
        hexacloud.core.event.EventBusManager.getGlobal().sub(hexacloud.core.cluster.event.ClusterEvent.NodeDeregistered.class, event -> triggerRedraw());
        hexacloud.core.event.EventBusManager.getGlobal().sub(hexacloud.core.cluster.event.ClusterEvent.ClusterRegistered.class, event -> triggerRedraw());
    }

    private void startInputReader() {
        hexacloud.core.utils.concurrent.ThreadManager.startVirtual("TuiInputReader", () -> {
            while (state.running) {
                int key = NativeTerminal.readKey();
                if (key != -1) {
                    synchronized (state) {
                        keyHandler.handleKeyPress(key);
                    }
                    triggerRedraw(true);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    private void executeRedrawLoop() {
        while (state.running) {
            try {
                // Block until an event releases the semaphore
                redrawSemaphore.acquire();
                
                if (bypassDebounce) {
                    bypassDebounce = false;
                } else {
                    // Debounce/Coalesce: sleep 15ms to group rapid multiple events
                    Thread.sleep(15);
                }
                redrawSemaphore.drainPermits();

                if (state.running) {
                    // Dynamically update state data before drawing
                    fetchClusterNames();
                    if (!state.selectedClusterName.isEmpty()) {
                        fetchNodeStatus();
                        fetchClusterConfig(state.selectedClusterName);
                    }
                    fetchGlobalConfig();

                    renderer.draw();
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void cleanup(hexacloud.core.event.EventListener<hexacloud.core.event.Event> interceptor) {
        DebugUtils.setLogListener(null);
        if (interceptor != null) {
            hexacloud.core.event.EventBusManager.getGlobal().removeInterceptor(interceptor);
        }
        NativeTerminal.resetTerminal();
        DebugUtils.setTuiModeActive(false);
        if (!readOnly && !isToggleMode) {
            // Stop all gateways if not read-only and not toggle mode
            for (RunningGatewayPort gw : activeGateways.values()) {
                try {
                    gw.stop();
                } catch (Exception ex) {
                    // Ignore
                }
            }
            System.exit(0);
        }
    }

    public void fetchClusterNames() {
        List<String> onlineNames = new ArrayList<>();
        List<String> offlineNames = new ArrayList<>();
        for (Cluster c : ClusterRegistry.getInstance().getClusters()) {
            String name = c.getClusterName();
            if (isGatewayActive(name)) {
                onlineNames.add(name);
            } else {
                offlineNames.add(name);
            }
        }
        onlineNames.sort(String::compareTo);
        offlineNames.sort(String::compareTo);
        
        List<String> sortedNames = new ArrayList<>();
        sortedNames.addAll(onlineNames);
        sortedNames.addAll(offlineNames);
        
        String previousSelected = state.selectedClusterName;
        state.clusterNames = sortedNames;
        
        if (!sortedNames.isEmpty()) {
            int index = sortedNames.indexOf(previousSelected);
            if (index != -1) {
                state.selectedClusterIndex = index;
            } else {
                state.selectedClusterIndex = 0;
                state.selectedClusterName = sortedNames.get(0);
            }
        } else {
            state.selectedClusterIndex = 0;
            state.selectedClusterName = "";
        }
        fetchGateways();
    }

    public void fetchGateways() {
        state.gateways.clear();
        for (String clusterName : state.clusterNames) {
            TuiState.GatewayConfig cfg = new TuiState.GatewayConfig();
            cfg.clusterName = clusterName;
            
            RunningGatewayPort activeGw = activeGateways.get(clusterName);
            if (activeGw != null) {
                cfg.gatewayName = activeGw.getGatewayName();
                cfg.port = activeGw.getPort();
                cfg.telnetEnabled = activeGw.isTelnetEnabled();
                cfg.httpEnabled = activeGw.isHttpEnabled();
                cfg.wsEnabled = activeGw.isWsEnabled();
                cfg.tcpProxyEnabled = activeGw.isTcpProxyEnabled();
                cfg.running = activeGw.isRunning();
                gatewayPorts.put(clusterName, activeGw.getPort());
            } else {
                Integer configuredPort = gatewayPorts.get(clusterName);
                cfg.port = (configuredPort != null) ? configuredPort : 3000;
                cfg.gatewayName = "gw-" + cfg.port;
                cfg.running = false;
            }
            state.gateways.add(cfg);
        }
    }

    public void fetchClusterConfig(String name) {
        if (name == null || name.isEmpty()) return;
        Cluster c = ClusterRegistry.getInstance().getCluster(name);
        if (c != null) {
            state.targetRequireToken = c.isRequireToken();
            state.targetTimeoutMs = c.getTimeoutMs();
            state.targetAllowedIps = c.getAllowedIps();
            state.targetRateLimitRequests = c.getRateLimitRequests();
            state.targetRateLimitDurationSeconds = c.getRateLimitDurationSeconds();
        }
    }

    public void fetchGlobalConfig() {
        state.globalPingInterval = hexacloud.core.config.ClusterConfig.DEFAULT_PING_INTERVAL_SECONDS;
    }

    public void fetchNodeStatus() {
        if (state.selectedClusterName.isEmpty()) return;
        Cluster c = ClusterRegistry.getInstance().getCluster(state.selectedClusterName);
        if (c != null) {
            state.nodes = c.getCluster();
        } else {
            state.nodes.clear();
        }
    }

    public void adjustServicesViewport(int visibleCount) {
        if (state.nodes.isEmpty()) {
            state.servicesViewportStart = 0;
            return;
        }
        if (state.selectedNodeIndex < 0) state.selectedNodeIndex = 0;
        if (state.selectedNodeIndex >= state.nodes.size()) state.selectedNodeIndex = state.nodes.size() - 1;
        
        if (state.selectedNodeIndex < state.servicesViewportStart) {
            state.servicesViewportStart = state.selectedNodeIndex;
        } else if (state.selectedNodeIndex >= state.servicesViewportStart + visibleCount) {
            state.servicesViewportStart = state.selectedNodeIndex - visibleCount + 1;
        }
    }

    public void adjustLogsViewport(int totalLogs, int visibleCount) {
        if (totalLogs == 0) {
            state.logViewportStart = 0;
            return;
        }
        if (state.selectedLogIndex < 0) state.selectedLogIndex = 0;
        if (state.selectedLogIndex >= totalLogs) state.selectedLogIndex = totalLogs - 1;
        
        if (state.selectedLogIndex < state.logViewportStart) {
            state.logViewportStart = state.selectedLogIndex;
        } else if (state.selectedLogIndex >= state.logViewportStart + visibleCount) {
            state.logViewportStart = state.selectedLogIndex - visibleCount + 1;
        }
    }
}
