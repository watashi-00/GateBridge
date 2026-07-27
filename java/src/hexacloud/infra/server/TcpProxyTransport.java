package hexacloud.infra.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.ServerTransport;
import hexacloud.core.server.filter.HttpFilter;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Concrete Layer 4 (Network Level) TCP Proxy Implementation of ServerTransport.
 * Accepts raw TCP connections, routes traffic via Round-Robin load balancing across
 * active cluster nodes, measures connection latency, and tunnels bytes bidirectionally using virtual threads.
 */
public class TcpProxyTransport implements ServerTransport {

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private volatile boolean active = true;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentLinkedQueue<byte[]> BUFFER_POOL = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private void configureSocket(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReceiveBufferSize(65536);
            socket.setSendBufferSize(65536);
        } catch (Exception ignored) {}
    }

    @Override
    public void listen(int port, RouteRegistry registry, List<Cluster> clusters, List<HttpFilter> customFilters) {
        new Thread(() -> serverListen(port, clusters), "TcpProxyServer-Listener-" + port).start();
    }

    private void serverListen(int port, List<Cluster> clusters) {
        DebugUtils.log("TcpProxyTransport starting to listen on port " + port);
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            DebugUtils.info("TcpProxyTransport successfully bound and listening on port " + port);
        } catch (IOException ex) {
            DebugUtils.error("TcpProxyTransport failed to bind on port " + port, ex);
            running = false;
            return;
        }

        try {
            while (active && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    configureSocket(clientSocket);
                    activeSockets.add(clientSocket);
                    ThreadManager.startVirtual("TcpProxy-Handler-" + clientSocket.getRemoteSocketAddress(), () -> handleConnection(clientSocket, clusters));
                } catch (IOException ex) {
                    if (active && !serverSocket.isClosed()) {
                        DebugUtils.error("TcpProxyTransport transient error accepting connection on port " + port, ex);
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        } finally {
            running = false;
        }
    }

    private void handleConnection(Socket clientSocket, List<Cluster> clusters) {
        Socket nodeSocket = null;
        try {
            // Collect all ONLINE TCP nodes across all clusters
            List<ServerNode> activeNodes = clusters.stream()
                    .filter(c -> c != null && c.getRoutingMode() != Cluster.RoutingMode.TELEMETRY_ONLY)
                    .flatMap(c -> c.getCluster().stream())
                    .filter(n -> n != null && n.status() == NodeStatus.ONLINE
                              && n.routingProtocol() == hexacloud.core.model.RoutingProtocol.TCP)
                    .collect(Collectors.toList());

            if (activeNodes.isEmpty()) {
                DebugUtils.log("TcpProxyTransport: No active TCP nodes available.");
                closeQuietly(clientSocket);
                return;
            }

            // Thread-safe Round-Robin node selection
            int index = (roundRobinIndex.getAndIncrement() & Integer.MAX_VALUE) % activeNodes.size();
            ServerNode selectedNode = activeNodes.get(index);

            String targetHost = selectedNode.getHostWithoutProtocol();
            int targetPort = selectedNode.port();

            // Connect to backend node and measure latency
            long startTime = System.currentTimeMillis();
            int timeout = 5000; // default timeout; per-cluster timeout applies at cluster level
            nodeSocket = new Socket();
            configureSocket(nodeSocket);
            nodeSocket.connect(new InetSocketAddress(targetHost, targetPort), timeout);
            long latencyMs = System.currentTimeMillis() - startTime;

            // Update passive telemetry & latency metric on the owning cluster
            selectedNode.setLatencyMs((int) latencyMs);
            for (Cluster c : clusters) {
                if (c.getCluster().stream().anyMatch(n -> n != null && n.getId().equals(selectedNode.getId()))) {
                    c.updateTelemetryServer(selectedNode.host(), selectedNode.port(), null, null, null, (int) latencyMs, null);
                    break;
                }
            }

            activeSockets.add(nodeSocket);

            Socket finalNodeSocket = nodeSocket;
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream nodeIn = finalNodeSocket.getInputStream();
            OutputStream nodeOut = finalNodeSocket.getOutputStream();

            // Bidirectional tunneling using virtual threads
            Thread t1 = ThreadManager.startVirtual("TcpProxy-ClientToNode", () -> tunnel(clientIn, nodeOut, finalNodeSocket));
            Thread t2 = ThreadManager.startVirtual("TcpProxy-NodeToClient", () -> tunnel(nodeIn, clientOut, clientSocket));

            // Wait for both tunneling threads to finish so cleanup can unregister active sockets
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                DebugUtils.log("TcpProxyTransport: Connection handler thread was interrupted.");
            }

        } catch (Exception e) {
            DebugUtils.error("TcpProxyTransport: Exception during socket proxying", e);
        } finally {
            if (nodeSocket != null) {
                closeQuietly(nodeSocket);
                activeSockets.remove(nodeSocket);
            }
            closeQuietly(clientSocket);
            activeSockets.remove(clientSocket);
        }
    }

    private void tunnel(InputStream in, OutputStream out, Socket outSocket) {
        byte[] buffer = BUFFER_POOL.poll();
        if (buffer == null) {
            buffer = new byte[8192];
        }
        try {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            BUFFER_POOL.offer(buffer);
            try {
                if (outSocket != null && !outSocket.isClosed() && !outSocket.isOutputShutdown()) {
                    outSocket.shutdownOutput();
                }
            } catch (IOException ignored) {}
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void stop() {
        active = false;
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                DebugUtils.error("Error closing TcpProxyTransport server socket", e);
            }
        }
        for (Socket s : activeSockets) {
            closeQuietly(s);
        }
        activeSockets.clear();
        DebugUtils.log("TcpProxyTransport stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
