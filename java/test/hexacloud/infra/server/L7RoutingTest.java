package hexacloud.infra.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.route.RouteRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class L7RoutingTest {

    private HttpServer backend1;
    private HttpServer backend2;
    private int backendPort1;
    private int backendPort2;
    private int gatewayPort;

    private HttpTransport transport;
    private Cluster testCluster;

    @BeforeEach
    public void setUp() throws Exception {
        backendPort1 = findFreePort();
        backendPort2 = findFreePort();
        gatewayPort = findFreePort();

        // Setup Backend 1
        backend1 = HttpServer.create(new InetSocketAddress(backendPort1), 0);
        backend1.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("X-Telemetry-CPU", "30.5");
                exchange.getResponseHeaders().set("X-Telemetry-RAM", "70.0");
                String method = exchange.getRequestMethod();
                String customHeader = exchange.getRequestHeaders().getFirst("X-Custom-Test");
                String body = "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    body = reader.lines().collect(Collectors.joining("\n"));
                }
                String respStr = "Response from Backend 1 | Method: " + method + " | Header: " + customHeader + " | Body: " + body;
                byte[] resp = respStr.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });
        backend1.start();

        // Setup Backend 2
        backend2 = HttpServer.create(new InetSocketAddress(backendPort2), 0);
        backend2.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("X-Telemetry-CPU", "40.0");
                exchange.getResponseHeaders().set("X-Telemetry-RAM", "80.0");
                String method = exchange.getRequestMethod();
                String customHeader = exchange.getRequestHeaders().getFirst("X-Custom-Test");
                String body = "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    body = reader.lines().collect(Collectors.joining("\n"));
                }
                String respStr = "Response from Backend 2 | Method: " + method + " | Header: " + customHeader + " | Body: " + body;
                byte[] resp = respStr.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });
        backend2.start();

        // Register Cluster
        testCluster = new Cluster("l7-test-cluster");
        testCluster.setRequireToken(false);
        testCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);

        ServerNode node1 = new ServerNode("node-1", "http://127.0.0.1", backendPort1, NodeStatus.ONLINE, false);
        ServerNode node2 = new ServerNode("node-2", "http://127.0.0.1", backendPort2, NodeStatus.ONLINE, false);

        testCluster.registerServer(node1);
        testCluster.registerServer(node2);

        RouteRegistry registry = new RouteRegistry();
        registry.getRoutes().put("CUSTOM_PATH", (args, out) -> {
            out.print("fast_path_buffered_output:" + args);
        });

        transport = new HttpTransport();
        transport.listen(gatewayPort, registry, testCluster, new ArrayList<>());

        waitUntilRunning(transport);
    }

    @AfterEach
    public void tearDown() {
        if (transport != null) {
            transport.stop();
        }
        if (backend1 != null) {
            backend1.stop(0);
        }
        if (backend2 != null) {
            backend2.stop(0);
        }
    }

    @Test
    public void testL7RoutingAndPassiveTelemetry() throws Exception {
        String urlStr = "http://127.0.0.1:" + gatewayPort + "/clusters/l7-test-cluster/api/data";

        // First Request
        String resp1 = sendGetRequest(urlStr);
        // Second Request
        String resp2 = sendGetRequest(urlStr);

        // Verify Round-Robin alternation
        assertNotEquals(resp1, resp2, "Requests should route to different nodes via Round-Robin");
        assertTrue(resp1.contains("Backend 1") || resp1.contains("Backend 2"));
        assertTrue(resp2.contains("Backend 1") || resp2.contains("Backend 2"));

        // Verify Passive Telemetry Update
        List<ServerNode> nodes = testCluster.getCluster();
        ServerNode n1 = nodes.stream().filter(n -> n.name().equals("node-1")).findFirst().orElse(null);
        ServerNode n2 = nodes.stream().filter(n -> n.name().equals("node-2")).findFirst().orElse(null);

        assertNotNull(n1);
        assertNotNull(n2);

        assertEquals(30.5, n1.cpuUsage(), 0.01);
        assertEquals(70.0, n1.ramUsage(), 0.01);
        assertTrue(n1.latencyMs() >= 0);

        assertEquals(40.0, n2.cpuUsage(), 0.01);
        assertEquals(80.0, n2.ramUsage(), 0.01);
        assertTrue(n2.latencyMs() >= 0);
    }

    @Test
    public void testTelemetryOnlyModeForbidden() throws Exception {
        testCluster.setRoutingMode(Cluster.RoutingMode.TELEMETRY_ONLY);

        String urlStr = "http://127.0.0.1:" + gatewayPort + "/clusters/l7-test-cluster/api/data";
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();

        assertEquals(403, responseCode, "Routing should be forbidden in TELEMETRY_ONLY mode");
    }

    @Test
    public void testForwardingHeadersAndBody() throws Exception {
        String urlStr = "http://127.0.0.1:" + gatewayPort + "/clusters/l7-test-cluster/api/submit";
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Custom-Test", "HeaderValue123");
        conn.setDoOutput(true);

        String requestBody = "payload_content_xyz";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int respCode = conn.getResponseCode();
        assertEquals(200, respCode);

        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String resp = in.lines().collect(Collectors.joining("\n"));
            assertTrue(resp.contains("POST"), "Should forward POST method");
            assertTrue(resp.contains("HeaderValue123"), "Should forward custom header");
            assertTrue(resp.contains("payload_content_xyz"), "Should forward body payload");
        }
    }

    @Test
    public void testNoActiveNodesServiceUnavailable() throws Exception {
        // Set all nodes offline
        for (ServerNode node : testCluster.getCluster()) {
            testCluster.updateStatusServer(node.getFullHost(), NodeStatus.OFFLINE);
        }

        String urlStr = "http://127.0.0.1:" + gatewayPort + "/clusters/l7-test-cluster/api/data";
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();

        assertEquals(503, responseCode, "Should return 503 Service Unavailable when no active nodes are present");
    }

    @Test
    public void testFastPathDirectRoute() throws Exception {
        String urlStr = "http://127.0.0.1:" + gatewayPort + "/custom_path?foo=bar";
        String resp = sendGetRequest(urlStr);
        assertEquals("fast_path_buffered_output:foo=bar", resp);
    }

    private String sendGetRequest(String urlStr) throws Exception {
        URL url = java.net.URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);

        assertEquals(200, conn.getResponseCode());

        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return in.lines().collect(Collectors.joining("\n"));
        }
    }

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitUntilRunning(HttpTransport transport) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (!transport.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(transport.isRunning());
    }
}
