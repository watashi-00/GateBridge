package hexacloud.infra.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.route.RouteRegistry;
import org.junit.jupiter.api.Test;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

public class L7TraceabilityHeadersTest {
    @Test
    public void testTraceabilityHeadersInjected() throws Exception {
        int backendPort = 18089;
        int gatewayPort = 18090;

        java.util.concurrent.atomic.AtomicReference<String> clientIpHeader = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> hostHeader = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> protoHeader = new java.util.concurrent.atomic.AtomicReference<>();

        ServerSocket serverSocket = new ServerSocket(backendPort);
        Thread t = new Thread(() -> {
            try (Socket s = serverSocket.accept();
                 java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
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

        Cluster cluster = new Cluster("trace-cluster");
        cluster.setRequireToken(false);
        cluster.setRoutingMode(Cluster.RoutingMode.HYBRID);
        ServerNode node = new ServerNode("trace-node", "http://127.0.0.1", backendPort, NodeStatus.ONLINE, false);
        cluster.registerServer(node);

        HttpTransport transport = new HttpTransport();
        transport.listen(gatewayPort, new RouteRegistry(), java.util.List.of(cluster), Collections.emptyList());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + gatewayPort + "/clusters/trace-cluster/")).build();
        try {
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}

        transport.stop();
        serverSocket.close();
        t.join();

        assertNotNull(clientIpHeader.get());
        assertEquals("127.0.0.1", clientIpHeader.get());
        assertEquals("127.0.0.1:" + gatewayPort, hostHeader.get());
        assertEquals("http", protoHeader.get());
    }
}
