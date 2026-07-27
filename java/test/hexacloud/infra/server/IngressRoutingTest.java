package hexacloud.infra.server;

import com.sun.net.httpserver.HttpServer;
import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.ClusterRegistry;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.server.route.RouteRule;
import hexacloud.core.server.route.RouteController;
import hexacloud.core.server.route.RouteMapping;
import hexacloud.core.server.route.ClusterController;
import java.io.PrintWriter;
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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class IngressRoutingTest {

    private HttpServer backend1;
    private HttpServer backend2;
    private int backendPort1;
    private int backendPort2;
    private int gatewayPort1;
    private int gatewayPort2;

    private HttpTransport jdkTransport;
    private UndertowHttpTransport undertowTransport;
    private Cluster testCluster;

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        backendPort1 = findFreePort();
        backendPort2 = findFreePort();
        gatewayPort1 = findFreePort();
        gatewayPort2 = findFreePort();

        // Setup Backend 1 (Normal backend node)
        backend1 = HttpServer.create(new InetSocketAddress(backendPort1), 0);
        backend1.createContext("/", exchange -> {
            byte[] resp = "Backend 1".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        backend1.start();

        // Setup Backend 2 (Telemetry-only backend node)
        backend2 = HttpServer.create(new InetSocketAddress(backendPort2), 0);
        backend2.createContext("/", exchange -> {
            byte[] resp = "Backend 2 Telemetry".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        backend2.start();

        // Register Cluster
        testCluster = new Cluster("ingress-test-cluster");
        testCluster.setRequireToken(false);
        testCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);

        ServerNode node1 = new ServerNode("node-1", "http://127.0.0.1", backendPort1, NodeStatus.ONLINE, false, hexacloud.core.model.PingProtocol.HTTP, "/", null, null, false, false);
        ServerNode node2 = new ServerNode("node-2", "http://127.0.0.1", backendPort2, NodeStatus.ONLINE, false, hexacloud.core.model.PingProtocol.HTTP, "/", null, null, false, true); // telemetryOnly = true

        testCluster.registerServer(node1);
        testCluster.registerServer(node2);

        ClusterRegistry.getInstance().clear();
        ClusterRegistry.getInstance().registerCluster(testCluster);
    }

    @AfterEach
    public void tearDown() {
        if (jdkTransport != null && jdkTransport.isRunning()) {
            jdkTransport.stop();
        }
        if (undertowTransport != null && undertowTransport.isRunning()) {
            undertowTransport.stop();
        }
        if (backend1 != null) {
            backend1.stop(0);
        }
        if (backend2 != null) {
            backend2.stop(0);
        }
        ClusterRegistry.getInstance().clear();
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private String sendGetBody(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    @Test
    public void testV1PrefixPeelingAndNodeFilterJdkTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        jdkTransport = new HttpTransport();
        jdkTransport.listen(gatewayPort1, registry, java.util.List.of(testCluster), Collections.emptyList());

        // Test /v1/clusters/ingress-test-cluster/api
        URL url = URI.create("http://127.0.0.1:" + gatewayPort1 + "/v1/clusters/ingress-test-cluster/api").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("Backend 1", body);
    }

    @Test
    public void testIngressRuleRoutingJdkTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.addRouteRule(new RouteRule("127.0.0.1", "/app/**", "ingress-test-cluster"));

        jdkTransport = new HttpTransport();
        jdkTransport.listen(gatewayPort1, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL url = URI.create("http://127.0.0.1:" + gatewayPort1 + "/app/users").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("Backend 1", body);
    }

    @Test
    public void testIngressRuleRoutingWithLocalhostHostAndAuthPatternJdkTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.addRouteRule(new RouteRule("localhost", "/auth/**", "ingress-test-cluster"));

        jdkTransport = new HttpTransport();
        jdkTransport.listen(gatewayPort1, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL url = URI.create("http://localhost:" + gatewayPort1 + "/auth/a").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("Backend 1", body);
    }

    @Test
    public void testIngressRuleRewritesBackendPathJdkTransport() throws Exception {
        int echoPort = findFreePort();
        HttpServer echoBackend = HttpServer.create(new InetSocketAddress(echoPort), 0);
        echoBackend.createContext("/", exchange -> {
            byte[] resp = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        echoBackend.start();

        try {
            Cluster rewriteCluster = new Cluster("rewrite-test-cluster");
            rewriteCluster.setRequireToken(false);
            rewriteCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
            rewriteCluster.registerServer(new ServerNode("rewrite-node", "http://localhost", echoPort, NodeStatus.ONLINE, false));

            RouteRegistry registry = new RouteRegistry();
            registry.addRouteRule(new RouteRule("localhost", "/auth/**", "rewrite-test-cluster"));
            registry.addRouteRule(new RouteRule("localhost", "/gateway/**", "rewrite-test-cluster", "/api"));

            jdkTransport = new HttpTransport();
            jdkTransport.listen(gatewayPort1, registry, java.util.List.of(testCluster), Collections.emptyList());

            assertEquals("/", sendGetBody("http://localhost:" + gatewayPort1 + "/auth/a"));
            assertEquals("/api/a", sendGetBody("http://localhost:" + gatewayPort1 + "/gateway/a"));
        } finally {
            echoBackend.stop(0);
        }
    }

    @Test
    public void testIngressRuleRewritesBackendPathUndertowTransport() throws Exception {
        int echoPort = findFreePort();
        HttpServer echoBackend = HttpServer.create(new InetSocketAddress(echoPort), 0);
        echoBackend.createContext("/", exchange -> {
            byte[] resp = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        echoBackend.start();

        try {
            Cluster rewriteCluster = new Cluster("rewrite-test-cluster-undertow");
            rewriteCluster.setRequireToken(false);
            rewriteCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
            rewriteCluster.registerServer(new ServerNode("rewrite-node", "http://localhost", echoPort, NodeStatus.ONLINE, false));

            RouteRegistry registry = new RouteRegistry();
            registry.addRouteRule(new RouteRule("localhost", "/auth/**", "rewrite-test-cluster-undertow"));
            registry.addRouteRule(new RouteRule("localhost", "/gateway/**", "rewrite-test-cluster-undertow", "/api"));

            undertowTransport = new UndertowHttpTransport();
            undertowTransport.listen(gatewayPort2, registry, java.util.List.of(testCluster), Collections.emptyList());

            assertEquals("/", sendGetBody("http://localhost:" + gatewayPort2 + "/auth/a"));
            assertEquals("/api/a", sendGetBody("http://localhost:" + gatewayPort2 + "/gateway/a"));
        } finally {
            echoBackend.stop(0);
        }
    }

    @Test
    public void testIngressRouteRuleBypassesTelemetryOnlyBlockUndertowTransport() throws Exception {
        int echoPort = findFreePort();
        HttpServer echoBackend = HttpServer.create(new InetSocketAddress(echoPort), 0);
        echoBackend.createContext("/", exchange -> {
            byte[] resp = "routed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        echoBackend.start();

        try {
            Cluster routeOnlyCluster = new Cluster("route-rule-telemetry-only-cluster");
            routeOnlyCluster.setRequireToken(false);
            routeOnlyCluster.setRoutingMode(Cluster.RoutingMode.TELEMETRY_ONLY);
            routeOnlyCluster.registerServer(new ServerNode("route-node", "http://localhost", echoPort, NodeStatus.ONLINE, false));

            RouteRegistry registry = new RouteRegistry();
            registry.addRouteRule(new RouteRule("localhost", "/auth/**", "route-rule-telemetry-only-cluster"));

            undertowTransport = new UndertowHttpTransport();
            undertowTransport.listen(gatewayPort2, registry, java.util.List.of(testCluster), Collections.emptyList());

            assertEquals("routed", sendGetBody("http://localhost:" + gatewayPort2 + "/auth/a"));
        } finally {
            echoBackend.stop(0);
        }
    }

    @Test
    public void testIngressRuleRoutingUndertowTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.addRouteRule(new RouteRule("127.0.0.1", "/app/**", "ingress-test-cluster"));

        undertowTransport = new UndertowHttpTransport();
        undertowTransport.listen(gatewayPort2, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL url = URI.create("http://127.0.0.1:" + gatewayPort2 + "/app/users").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("Backend 1", body);
    }

    @Test
    public void testTelemetryOnlyNodesExcludedJdkTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        Cluster telemetryOnlyCluster = new Cluster("telemetry-only-cluster");
        telemetryOnlyCluster.setRequireToken(false);
        telemetryOnlyCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
        ServerNode node = new ServerNode("node-t", "http://127.0.0.1", backendPort2, NodeStatus.ONLINE, false, hexacloud.core.model.PingProtocol.HTTP, "/", null, null, false, true);
        telemetryOnlyCluster.registerServer(node);
        ClusterRegistry.getInstance().registerCluster(telemetryOnlyCluster);

        jdkTransport = new HttpTransport();
        jdkTransport.listen(gatewayPort1, registry, java.util.List.of(telemetryOnlyCluster), Collections.emptyList());

        URL url = URI.create("http://127.0.0.1:" + gatewayPort1 + "/clusters/telemetry-only-cluster/data").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(503, conn.getResponseCode());
    }

    @Test
    public void testTelemetryOnlyNodesExcludedUndertowTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        Cluster telemetryOnlyCluster = new Cluster("telemetry-only-cluster-undertow");
        telemetryOnlyCluster.setRequireToken(false);
        telemetryOnlyCluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
        ServerNode node = new ServerNode("node-t", "http://127.0.0.1", backendPort2, NodeStatus.ONLINE, false, hexacloud.core.model.PingProtocol.HTTP, "/", null, null, false, true);
        telemetryOnlyCluster.registerServer(node);
        ClusterRegistry.getInstance().registerCluster(telemetryOnlyCluster);

        undertowTransport = new UndertowHttpTransport();
        undertowTransport.listen(gatewayPort2, registry, java.util.List.of(telemetryOnlyCluster), Collections.emptyList());

        URL url = URI.create("http://127.0.0.1:" + gatewayPort2 + "/clusters/telemetry-only-cluster-undertow/data").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(503, conn.getResponseCode());
    }

    @Test
    public void testLocalRoutePriorityJdkTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.registerController(new RouteController() {
            @RouteMapping("TEST_LOCAL")
            public void testLocal(String args, PrintWriter out) {
                out.print("LOCAL RESPONSE");
            }
        });
        registry.addRouteRule(new RouteRule("127.0.0.1", "/**", "ingress-test-cluster"));

        jdkTransport = new HttpTransport();
        jdkTransport.listen(gatewayPort1, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL url = URI.create("http://127.0.0.1:" + gatewayPort1 + "/test_local").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("LOCAL RESPONSE", body);
    }

    @Test
    public void testRootDoesNotExposeGetNodesJdkTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.registerController(new ClusterController(testCluster));

        jdkTransport = new HttpTransport();
        jdkTransport.listen(gatewayPort1, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL rootUrl = URI.create("http://127.0.0.1:" + gatewayPort1 + "/").toURL();
        HttpURLConnection rootConn = (HttpURLConnection) rootUrl.openConnection();
        rootConn.setRequestMethod("GET");

        assertEquals(404, rootConn.getResponseCode());
        String rootBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(rootConn.getErrorStream(), StandardCharsets.UTF_8))) {
            rootBody = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("404 Not Found - Unknown Route: /", rootBody);

        URL explicitUrl = URI.create("http://127.0.0.1:" + gatewayPort1 + "/get_nodes").toURL();
        HttpURLConnection explicitConn = (HttpURLConnection) explicitUrl.openConnection();
        explicitConn.setRequestMethod("GET");

        assertEquals(200, explicitConn.getResponseCode());
    }

    @Test
    public void testRootDoesNotExposeGetNodesUndertowTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.registerController(new ClusterController(testCluster));

        undertowTransport = new UndertowHttpTransport();
        undertowTransport.listen(gatewayPort2, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL rootUrl = URI.create("http://127.0.0.1:" + gatewayPort2 + "/").toURL();
        HttpURLConnection rootConn = (HttpURLConnection) rootUrl.openConnection();
        rootConn.setRequestMethod("GET");

        assertEquals(404, rootConn.getResponseCode());
        String rootBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(rootConn.getErrorStream(), StandardCharsets.UTF_8))) {
            rootBody = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("404 Not Found - Unknown Route: /", rootBody);

        URL explicitUrl = URI.create("http://127.0.0.1:" + gatewayPort2 + "/get_nodes").toURL();
        HttpURLConnection explicitConn = (HttpURLConnection) explicitUrl.openConnection();
        explicitConn.setRequestMethod("GET");

        assertEquals(200, explicitConn.getResponseCode());
    }

    @Test
    public void testLocalRoutePriorityUndertowTransport() throws Exception {
        RouteRegistry registry = new RouteRegistry();
        registry.registerController(new RouteController() {
            @RouteMapping("TEST_LOCAL")
            public void testLocal(String args, PrintWriter out) {
                out.print("LOCAL RESPONSE");
            }
        });
        registry.addRouteRule(new RouteRule("127.0.0.1", "/**", "ingress-test-cluster"));

        undertowTransport = new UndertowHttpTransport();
        undertowTransport.listen(gatewayPort2, registry, java.util.List.of(testCluster), Collections.emptyList());

        URL url = URI.create("http://127.0.0.1:" + gatewayPort2 + "/test_local").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }
        assertEquals("LOCAL RESPONSE", body);
    }
}
