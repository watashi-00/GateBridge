package hexacloud.application;

import hexacloud.core.event.Event;
import hexacloud.core.event.EventController;
import hexacloud.core.event.EventFormat;
import hexacloud.core.event.Subscribe;
import hexacloud.core.cluster.event.ClusterEvent;
import hexacloud.core.model.PingProtocol;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.route.RouteController;
import hexacloud.core.server.route.RouteMapping;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.ports.GatewayBuilderPort;
import hexacloud.core.ports.RunningGatewayPort;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;
import hexacloud.infra.gateway.GatewayFactory;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;

/**
 * Standalone minimal example application demonstrating the full set of framework features
 * programmatically without launching the Terminal User Interface (TUI).
 * 
 * Configures security, rate-limiting, custom route controllers, and custom event listeners.
 */
public class MinimalApplication {

    public static void main(String[] args) {
        new MinimalApplication().run();
    }

    public void run() {
        // Enable log prints for maximum visibility in the console output
        DebugUtils.setDebugEnabled(false);
        System.out.println("=== Starting GateBridge Minimal Demo Application (No TUI) ===");

        // 1. Programmatic Bootstrapping with Fluent Config API
        // Style A: With custom gatewayName (e.g. "minimal-gw")
        // Style B: Without .gatewayName(), falls back to default "gw-" + port (e.g. "gw-4000")
        GatewayBuilderPort builder = GatewayFactory.createGateway("minimal-gw")
            .createCluster("demo-cluster")
            .port(4000)                   // Base Telnet port (HTTP runs on 4001, Websocket on 4002)
            .pingInterval(3)              // Ping checks scheduled every 3 seconds
            .enableHttp(true)             // Enable HTTP API mapping
            .enableTelnet(true)           // Enable Telnet socket server
            .enableWs(true)               // Enable WebSocket events stream
            .registerController(new DemoRouteController())
            .requireToken(true, "demo-secret-key-999")
            .rateLimit(150, 60)           // Limit: 150 requests per 60 seconds
            .allowedIps("127.0.0.1, localhost")
            .timeout(3000);               // Connection timeout threshold of 3 seconds

        // 2. Register Server Nodes with explicit protocol/health-check settings
        builder.registerServer(new ServerNode(
                "http://localhost", 3001, NodeStatus.OFFLINE, false,
                PingProtocol.HTTP, "/health", "Authorization", "Bearer demo-secret-key-999"
            ))
            .registerServer(new ServerNode(
                "http://localhost", 3002, NodeStatus.OFFLINE, false,
                PingProtocol.GRPC, "/grpc-health", null, null
            ))
            .registerServer(new ServerNode(
                "http://localhost", 3003, NodeStatus.OFFLINE, true,
                PingProtocol.WEBSOCKET, "/ws", null, null
            ));

        // 3. Start listeners and schedule pings
        RunningGatewayPort runningGateway = builder.listen()
            .startPingScheduler();

        System.out.println("Gateway successfully listening on port 4000 (Telnet), 4001 (HTTP), 4002 (WS)");

        // 4. Dispatch a custom event to verify listener registration
        runningGateway.eventManager().dispatch(new DeveloperCustomEvent("GateBridge Bootstrapped Successfully!"));
        runningGateway.eventManager().dispatch(new ClusterEvent.NodeEventSubmitted(
            "http://localhost:3001",
            3001,
            PingProtocol.HTTP,
            EventFormat.JSON,
            "bootstrap.ready",
            Map.of(
                "source", "MinimalApplication",
                "cluster", runningGateway.getClusterName()
            )
        ));

        // 5. Run a background monitoring loop to print system stats and thread breakdown every 5 seconds
        ThreadManager.startVirtual("SystemMonitor", () -> {
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            Runtime runtime = Runtime.getRuntime();
            while (true) {
                try {
                    Thread.sleep(5000);
                    int threadCount = threadMXBean.getThreadCount();
                    
                    int appThreads = 0;
                    int jvmThreads = 0;
                    try {
                        java.util.Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
                        for (Thread t : threadSet) {
                            String name = t.getName();
                            if (t.isDaemon() && (name.contains("ForkJoinPool") || name.contains("VirtualThread-unblocker") ||
                                name.equals("Reference Handler") || name.equals("Finalizer") || 
                                name.equals("Signal Dispatcher") || name.equals("Notification Thread") || 
                                name.equals("Common-Cleaner") || name.equals("Attach Listener"))) {
                                jvmThreads++;
                            } else {
                                appThreads++;
                            }
                        }
                    } catch (Throwable t) {
                        appThreads = 1;
                        jvmThreads = threadCount - 1;
                    }
                    
                    long freeMem = runtime.freeMemory() / (1024 * 1024);
                    long totalMem = runtime.totalMemory() / (1024 * 1024);
                    long usedMem = totalMem - freeMem;
                    System.out.printf("[MONITOR] Total OS Threads: %d (App/Framework: %d, JVM System Services: %d) | RAM Used: %d MB / %d MB\n", 
                        threadCount, appThreads, jvmThreads, usedMem, totalMem);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        // 6. Launch the DevOps Dashboard in non-blocking toggle mode (detach/reattach with ENTER)
        hexacloud.core.tui.TerminalUiFactory.createTui("GateBridge Minimal DevOps Panel")
            .seedGateway(runningGateway)
            .startToggleMode();
    }

    // ==========================================
    // CUSTOM DISCOVERABLE EVENT SUBSYSTEM
    // ==========================================

    public static class DeveloperCustomEvent implements Event {
        private final String message;

        public DeveloperCustomEvent(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public static class DemoEventController implements EventController {

        @Subscribe
        public void onDeveloperEvent(DeveloperCustomEvent event) {
            System.out.println("[EVENT] Developer Custom Event Received: " + event.message());
        }

        @Subscribe
        public void onNodeStatusChanged(ClusterEvent.NodeStatusChanged event) {
            System.out.printf("[EVENT] Node Status Changed -> Host: %s | Status: %s\n", 
                event.host(), event.status());
        }

        @Subscribe
        public void onNodeTelemetryUpdated(ClusterEvent.NodeTelemetryUpdated event) {
            System.out.printf("[EVENT] Telemetry Updated for Node: %s\n", event.host());
        }

        @Subscribe
        public void onNodeEventSubmitted(ClusterEvent.NodeEventSubmitted event) {
            System.out.printf(
                "[EVENT] Custom Node Event -> Host: %s | Port: %d | Protocol: %s | Format: %s | Event: %s | Attributes: %s\n",
                event.host(), event.port(), event.protocol(), event.format(), event.event(), event.attributes()
            );
        }
    }

    // ==========================================
    // CUSTOM DISCOVERABLE ROUTE CONTROLLER
    // ==========================================

    public static class DemoRouteController implements RouteController {

        @RouteMapping("HELLO")
        public void handleHello(String args, PrintWriter out) {
            out.println("HELLO FROM MINIMAL APPLICATION ROUTE!");
            out.println("Arguments received: " + (args.isEmpty() ? "None" : args));
        }

        @RouteMapping("SYSTEM_INFO")
        public void handleSystemInfo(String args, PrintWriter out) {
            out.println("GateBridge Framework Status: ACTIVE");
            out.println("Available Processors: " + Runtime.getRuntime().availableProcessors());
        }
    }
}
