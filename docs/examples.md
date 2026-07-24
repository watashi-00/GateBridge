# Usage Examples & Showcase

This page collects common GateBridge bootstrapping examples and highlights the polyglot client nodes available in the showcase directory.

## Bootstrapping a Gateway & TUI

Using `TerminalUiFactory` and `TerminalUiPort` makes launching the console dashboard fluent:

```java
import hexacloud.core.ports.GatewayBuilderPort;
import hexacloud.core.ports.RunningGatewayPort;
import hexacloud.core.ports.TerminalUiPort;
import hexacloud.core.tui.TerminalUiFactory;
import hexacloud.infra.gateway.GatewayFactory;

public class AppBootstrap {
    public static void main(String[] args) {
        // 1. Build and configure the gateway listener ports with explicit names
        GatewayBuilderPort builder = GatewayFactory.createGateway("gateway-1", "production-cluster")
            .port(8080)
            .pingInterval(10)
            .enableHttp(true)
            .enableTelnet(true)
            .enableTcpProxy(true); // Enables L4 TCP Proxy on base_port + 3 (8083)

        // 2. Set routing mode on the cluster
        builder.getCluster().setRoutingMode(Cluster.RoutingMode.HYBRID);

        // 3. Register a named node
        builder.registerNode("node-a", "http://localhost", 8081).register();

        // 4. Launch listeners and checks explicitly
        RunningGatewayPort gateway = builder.listen().startPingScheduler();

        // 5. Obtain the TUI control panel and lock permissions
        TerminalUiPort controlPanel = TerminalUiFactory.createTui("Hexacloud Admin Desk")
            .seedGateway(gateway)
            .readOnly(false)
            .nodeManagementEnabled(true)
            .gatewayManagementEnabled(true);

        // 6. Run the interactive terminal interface
        controlPanel.start();
    }
}
```

## Zero-Configuration Dynamic Startup

If your gateway state is already persisted inside `.state/hexacloud-state.properties`, you can bootstrap the entire monitor and gateway server network with a single line of code:

```java
import hexacloud.core.tui.TerminalUiFactory;

public class PureTerminalLauncher {
    public static void main(String[] args) {
        TerminalUiFactory.createTui("Hexacloud Dynamic Dashboard").start();
    }
}
```

## Polyglot Node Showcase (`show_case/`)

The repository contains concrete client nodes written in various languages inside the [`show_case/`](../show_case/) folder to simulate real production service workloads:

### 1. Node.js Client Node (`node_node.js`)
*   **Protocol:** Persistent WebSocket connection (built-in Node `WebSocket` client).
*   **Functionality:** Establishes a persistent connection to the Gateway's WebSocket event stream (on port `base_port + 2`) to receive and display real-time push telemetry and custom events.
*   **Command:** `node show_case/node_node.js`

### 2. Python Client Node (`python_node.py`)
*   **Protocol:** Registers itself via HTTP POST endpoint requests to `/register`.
*   **Functionality:** Starts an HTTP server on port `4002` that answers health checks with a custom auth header token (`Bearer showCaseToken`).
*   **Command:** `python3 show_case/python_node.py`

### 3. Go Client Node (`go_node.go`)
*   **Protocol:** Registers via HTTP POST request.
*   **Functionality:** Standardized Go HTTP router listening on port `4003` responding with JSON status values on `/` route checks.
*   **Command:** `go run show_case/go_node.go`

### 4. Bash DevOps Script (`bash_control.sh`)
*   **Protocol:** Telnet Netcat socket and raw curl checks.
*   **Functionality:** DevOps querying automation showing list registers and status telemetry.
*   **Command:** `./show_case/bash_control.sh`
