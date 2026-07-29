package hexacloud.infra.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.ports.GatewayBuilderPort;
import hexacloud.core.ports.RunningGatewayPort;
import hexacloud.core.server.HttpEngine;
import hexacloud.core.server.ServerTransport;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.infra.gateway.GatewayFactory;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class L7TraceabilityHeadersTest {

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private void runTraceabilityTest(ServerTransport transport, boolean sendExistingXff) throws Exception {
        AtomicReference<String> clientIpHeader = new AtomicReference<>();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        AtomicReference<String> protoHeader = new AtomicReference<>();

        ServerSocket backendSocket = new ServerSocket(0);
        int backendPort = backendSocket.getLocalPort();
        int gatewayPort = findFreePort();

        Thread t = new Thread(() -> {
            try (Socket s = backendSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 OutputStream out = s.getOutputStream()) {
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("X-Forwarded-For: ")) clientIpHeader.set(line.substring(17));
                    if (line.startsWith("X-Forwarded-Host: ")) hostHeader.set(line.substring(18));
                    if (line.startsWith("X-Forwarded-Proto: ")) protoHeader.set(line.substring(19));
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());
                out.flush();
            } catch (Exception ignored) {}
        });
        t.start();

        Cluster cluster = new Cluster("trace-cluster-" + System.nanoTime());
        cluster.setRequireToken(false);
        cluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
        ServerNode node = new ServerNode("trace-node", "http://127.0.0.1", backendPort, NodeStatus.ONLINE, false);
        cluster.registerServer(node);

        transport.listen(gatewayPort, new RouteRegistry(), List.of(cluster), Collections.emptyList());

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + gatewayPort + "/clusters/" + cluster.getClusterName() + "/"));

            if (sendExistingXff) {
                reqBuilder.header("X-Forwarded-For", "1.2.3.4");
            }

            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());

            t.join(3000);

            assertNotNull(clientIpHeader.get(), "X-Forwarded-For header should not be null");
            if (sendExistingXff) {
                assertEquals("1.2.3.4, 127.0.0.1", clientIpHeader.get());
            } else {
                assertEquals("127.0.0.1", clientIpHeader.get());
            }
            assertEquals("127.0.0.1:" + gatewayPort, hostHeader.get());
            assertEquals("http", protoHeader.get());
        } finally {
            transport.stop();
            backendSocket.close();
        }
    }

    @Test
    public void testHttpTransportTraceabilityHeaders() throws Exception {
        runTraceabilityTest(new HttpTransport(), false);
    }

    @Test
    public void testUndertowHttpTransportTraceabilityHeaders() throws Exception {
        runTraceabilityTest(new UndertowHttpTransport(), false);
    }

    @Test
    public void testUndertowHttpTransportWithGatewayEngine() throws Exception {
        ServerSocket backendSocket = new ServerSocket(0);
        int backendPort = backendSocket.getLocalPort();
        int basePort = findFreePort();
        int httpPort = basePort + 1;

        AtomicReference<String> clientIpHeader = new AtomicReference<>();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        AtomicReference<String> protoHeader = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try (Socket s = backendSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 OutputStream out = s.getOutputStream()) {
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("X-Forwarded-For: ")) clientIpHeader.set(line.substring(17));
                    if (line.startsWith("X-Forwarded-Host: ")) hostHeader.set(line.substring(18));
                    if (line.startsWith("X-Forwarded-Proto: ")) protoHeader.set(line.substring(19));
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());
                out.flush();
            } catch (Exception ignored) {}
        });
        t.start();

        String clusterName = "gw-trace-cluster-" + System.nanoTime();
        GatewayBuilderPort gatewayBuilder = GatewayFactory.createGateway("trace-gateway-" + System.nanoTime());
        gatewayBuilder.createCluster(clusterName);
        gatewayBuilder.getCluster().setRequireToken(false);
        gatewayBuilder.getCluster().setRoutingMode(Cluster.RoutingMode.HYBRID);
        gatewayBuilder.registerServer(new ServerNode("trace-node", "http://127.0.0.1", backendPort, NodeStatus.ONLINE, false));
        gatewayBuilder.httpEngine(HttpEngine.UNDERTOW)
                .enableHttp(true);

        RunningGatewayPort gateway = gatewayBuilder.listen(basePort);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + httpPort + "/clusters/" + clusterName + "/"))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());

            t.join(3000);

            assertNotNull(clientIpHeader.get(), "X-Forwarded-For header should not be null");
            assertEquals("127.0.0.1", clientIpHeader.get());
            assertEquals("127.0.0.1:" + httpPort, hostHeader.get());
            assertEquals("http", protoHeader.get());
        } finally {
            gateway.stop();
            backendSocket.close();
        }
    }

    @Test
    public void testXForwardedForConcatenationHttpTransport() throws Exception {
        runTraceabilityTest(new HttpTransport(), true);
    }

    @Test
    public void testXForwardedForConcatenationUndertowHttpTransport() throws Exception {
        runTraceabilityTest(new UndertowHttpTransport(), true);
    }
}
