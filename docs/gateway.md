# Gateway & Node Configuration

GateBridge gateways are created through `GatewayFactory` and exposed as `GatewayBuilderPort` during configuration and `RunningGatewayPort` at runtime.

## Create a Gateway

The main entry point is:

```java
GatewayBuilderPort builder = GatewayFactory.createGateway("gateway-1", "my-cluster")
    .port(3000)
    .pingInterval(5)
    .enableTelnet(true)
    .enableHttp(true)
    .enableWs(true)
    .enableTcpProxy(true); // Enables Layer 4 TCP proxy load balancing
```

This creates a local gateway adapter with a specific gateway name and cluster name, sets the base transport port, and selects transport protocol listeners (including L4 TCP proxy).

## Registering Nodes with NodeBuilder

To support advanced telemetry, authorization, and custom health check paths, GateBridge provides a fluent `NodeBuilder` API:

```java
builder.registerNode("node-1", "http://localhost", 3005)
    .pingProtocol(PingProtocol.HTTP)
    .pingPath("/healthz")
    .pingHeader("Authorization", "Bearer token123")
    .register();
```

*   **`registerNode(name, host, port)`** — Specifies the node name, host target, and socket port.
*   **`pingProtocol(PingProtocol)`** — Toggles and configures the active health check protocol (`HTTP`, `WEBSOCKET`, `TCP`, `UDP`, `GRPC`, or `NONE` for Push-only).
*   **`pingPath(path)`** — Changes the URI path for active health check requests.
*   **`pingHeader(name, value)`** — Appends custom authentication headers to ping checks.

For simpler registrations, you can still register a node quickly:

```java
builder.registerServer(3001, NodeStatus.OFFLINE);
```

## State Persistence Layer

The gateway features automatic state persistence:
- Whenever a cluster is modified, nodes are registered/deregistered, or configurations change (IP lists, rate limits, timeouts), GateBridge serializes the current cluster state to `.state/<clusterName>-state.properties`.
- When `GatewayFactory.createGateway()` is called, it automatically loads any existing configuration. If a state file is loaded, hardcoded developer bootstrap configurations are skipped so user edits are not overwritten.
- Sensitive values are excluded from state files. Cluster secrets and ping header token values are runtime-only and should be supplied through code, environment configuration, or a secret manager.

## Lifecycle Management (`stop()`)

Both `RunningGatewayPort` and `ServerManager` support dynamic lifecycle shutdown:

```java
runningGateway.stop();
```

This immediately releases all active network listeners (HTTP, Telnet, WebSockets) and cleanly terminates the background health-check scheduler without interrupting the JVM.

## Push-Based Passive Telemetry

Nodes can push their telemetry metrics actively using the REST API:
- **HTTP Path:** `/clusters/<clusterName>/telemetry?host=<host>&port=<port>&cpu=<cpu>&ram=<ram>&language=<language>&status=<status>&event=<eventName>&protocol=<protocol>&format=<format>`
- **Telnet Command:** `TELEMETRY <host> <port> cpu=<cpu> ram=<ram> language=<language> status=<status> event=<eventName> protocol=<protocol> format=<format>`

The optional `event` parameter dispatches a `ClusterEvent.NodeEventSubmitted` event. `protocol` and `format` describe how the source service produced the event, for example `protocol=grpc format=json` or `protocol=ws format=cloudevent`. Any extra key/value parameters are forwarded as event attributes, except reserved fields and authentication tokens.

Refer to [Ping Health-Check Contracts](ping-api-contract.md) for full details.

## Cluster Routing Modes

Clusters can be configured in one of three routing modes to explicitly define behavior:

*   **`TELEMETRY_ONLY`** — Performs active/passive health and resource telemetry collecting without enabling request proxying. Load balancer routing is blocked with `403 Forbidden`.
*   **`LOAD_BALANCER_ONLY`** — Performs dynamic request routing (L4 TCP proxying and L7 HTTP proxying) without starting any background ping health checks.
*   **`HYBRID`** — Combines active health monitoring pings with dynamic request routing and passive telemetry collection.

To configure a routing mode:

```java
Cluster cluster = builder.getCluster();
cluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
```

## Layer 7 HTTP Reverse Proxy Load-Balancer

When a cluster is in `LOAD_BALANCER_ONLY` or `HYBRID` mode, any incoming HTTP request targeting the REST server on path `/clusters/{clusterName}/{path}` will be proxied:
1.  GateBridge selects an active node using thread-safe, overflow-safe Round-Robin.
2.  The request method, headers, and body are forwarded to the selected node's backend address.
3.  The response body is streamed back using chunked transfer encoding (`Transfer-Encoding: chunked`) to prevent JVM heap OOMs.
4.  Connection latency is measured passively, and CPU/RAM parameters are extracted from response headers (`X-Telemetry-CPU`, `X-Telemetry-RAM`) to update node state.
5.  If target node connection fails, the client receives a `502 Bad Gateway` response.

## Layer 4 TCP Proxy Tunneling Load-Balancer

When enabled via `.enableTcpProxy(true)`, GateBridge starts a raw Layer 4 TCP proxy on `basePort + 3`:
*   Spawns virtual threads (`ThreadManager.startVirtual`) to tunnel data bidirectionally between client and backend node.
*   Uses Round-Robin node selection to distribute raw TCP streams.
*   Handles TCP half-close sequences natively via output shutdown, preserving active tunnels while ensuring clean socket closure upon termination.
*   Connection latency is passively tracked and updated in the telemetry dashboard.

## Complete Bootstrap Example

```java
// Create gateway with explicit gateway name and cluster name
GatewayBuilderPort builder = GatewayFactory.createGateway("gateway-1", "production-cluster")
    .port(3000)
    .pingInterval(5)
    .enableTelnet(true)
    .enableHttp(true)
    .enableWs(true)
    .enableTcpProxy(true); // Enables L4 TCP Proxy load-balancing

// Configure routing mode
builder.getCluster().setRoutingMode(Cluster.RoutingMode.HYBRID);

// Register node with name, host, and port
builder.registerNode("node-a", "http://localhost", 3001)
    .pingProtocol(PingProtocol.HTTP)
    .pingPath("/health")
    .pingHeader("X-Token", "secret")
    .register();

// Launch listeners and health pings explicitly
RunningGatewayPort runningGateway = builder.listen()
       .startPingScheduler();

// Close down resources when done
// runningGateway.stop();
```
