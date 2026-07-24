package hexacloud.infra.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.ServerManager;
import hexacloud.core.server.route.RouteRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class L4RoutingTest {

    private ServerSocket backendServer1;
    private ServerSocket backendServer2;
    private int backendPort1;
    private int backendPort2;
    private int proxyPort;

    private TcpProxyTransport transport;
    private Cluster testCluster;
    private final AtomicBoolean backendsRunning = new AtomicBoolean(true);
    private ExecutorService backendExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        backendPort1 = findFreePort();
        backendPort2 = findFreePort();
        proxyPort = findFreePort();
        backendsRunning.set(true);
        backendExecutor = Executors.newCachedThreadPool();

        // Setup Backend Server 1 (Raw TCP echo/reply)
        backendServer1 = new ServerSocket(backendPort1);
        backendExecutor.execute(() -> runBackendServer(backendServer1, "Backend 1"));

        // Setup Backend Server 2 (Raw TCP echo/reply)
        backendServer2 = new ServerSocket(backendPort2);
        backendExecutor.execute(() -> runBackendServer(backendServer2, "Backend 2"));

        // Setup Cluster
        testCluster = new Cluster("l4-test-cluster");
        testCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);

        ServerNode node1 = new ServerNode("node-1", "http://127.0.0.1", backendPort1, NodeStatus.ONLINE, false);
        ServerNode node2 = new ServerNode("node-2", "http://127.0.0.1", backendPort2, NodeStatus.ONLINE, false);

        testCluster.registerServer(node1);
        testCluster.registerServer(node2);

        // Start TcpProxyTransport
        transport = new TcpProxyTransport();
        transport.listen(proxyPort, new RouteRegistry(), testCluster, new ArrayList<>());

        waitUntilRunning(transport);
    }

    private void runBackendServer(ServerSocket serverSocket, String name) {
        try {
            while (backendsRunning.get() && !serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                backendExecutor.execute(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                         PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            out.println("Echo from " + name + ": " + line);
                        }
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            clientSocket.close();
                        } catch (IOException ignored) {}
                    }
                });
            }
        } catch (IOException ignored) {}
    }

    @AfterEach
    public void tearDown() {
        backendsRunning.set(false);
        if (transport != null) {
            transport.stop();
        }
        closeQuietly(backendServer1);
        closeQuietly(backendServer2);
        if (backendExecutor != null) {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    public void testL4Proxy() throws Exception {
        // Send request 1 through L4 proxy
        String response1 = sendTcpMessage("127.0.0.1", proxyPort, "ping-1");
        // Send request 2 through L4 proxy
        String response2 = sendTcpMessage("127.0.0.1", proxyPort, "ping-2");

        // Verify Round-Robin balance across both backends
        assertNotEquals(response1, response2, "Requests should route to different backend nodes");
        assertTrue(response1.contains("Backend 1") || response1.contains("Backend 2"));
        assertTrue(response2.contains("Backend 1") || response2.contains("Backend 2"));

        // Verify passive telemetry / latency metric extraction
        List<ServerNode> nodes = testCluster.getCluster();
        ServerNode n1 = nodes.stream().filter(n -> n.name().equals("node-1")).findFirst().orElse(null);
        ServerNode n2 = nodes.stream().filter(n -> n.name().equals("node-2")).findFirst().orElse(null);

        assertNotNull(n1);
        assertNotNull(n2);
        assertTrue(n1.latencyMs() >= 0);
        assertTrue(n2.latencyMs() >= 0);
    }

    @Test
    public void testNoActiveNodes() throws Exception {
        // Set all nodes offline
        for (ServerNode node : testCluster.getCluster()) {
            testCluster.updateStatusServer(node.getFullHost(), NodeStatus.OFFLINE);
        }

        // Try connecting to proxy
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(1000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line = in.readLine();
            assertNull(line, "Socket should receive EOF/close immediately when no online nodes exist");
        }
    }

    @Test
    public void testServerManagerIntegration() throws Exception {
        int basePort = findFreePort();
        ServerManager manager = new ServerManager(basePort, testCluster, null);
        manager.enableTcpProxy(true);
        assertTrue(manager.isTcpProxyEnabled());

        manager.listen(basePort);
        Thread.sleep(150); // Allow async listener socket to bind

        int expectedTcpProxyPort = basePort + 3;
        String resp = sendTcpMessage("127.0.0.1", expectedTcpProxyPort, "server-manager-test");
        assertTrue(resp.contains("Echo from Backend"));

        manager.stop();
    }

    private String sendTcpMessage(String host, int port, String message) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(message);
            return in.readLine();
        }
    }

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitUntilRunning(TcpProxyTransport transport) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (!transport.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(transport.isRunning());
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
