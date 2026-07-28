package hexacloud.infra.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.route.RouteRegistry;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

public class L4ProxyTimeoutTest {
    @Test
    public void testTcpSoTimeoutTriggered() throws Exception {
        int backendPort;
        int proxyPort;
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            backendPort = s1.getLocalPort();
            proxyPort = s2.getLocalPort();
        }

        ServerSocket backend = new ServerSocket(backendPort);
        // Spin up backend that accepts but never sends data
        Thread t = new Thread(() -> {
            try (Socket s = backend.accept()) {
                Thread.sleep(2000);
            } catch (Exception ignored) {}
        });
        t.start();

        Cluster cluster = new Cluster("l4-timeout");
        ServerNode node = new ServerNode("tcp-node", "http://127.0.0.1", backendPort, NodeStatus.ONLINE, false)
                .withRoutingProtocol(hexacloud.core.model.RoutingProtocol.TCP);
        cluster.registerServer(node);

        TcpProxyTransport transport = new TcpProxyTransport();
        transport.setSoTimeout(500); // 500ms timeout
        transport.listen(proxyPort, new RouteRegistry(), java.util.List.of(cluster), Collections.emptyList());

        // Wait a bit for transport to start listening
        long deadline = System.currentTimeMillis() + 2000;
        while (!transport.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(transport.isRunning(), "Transport should be running");

        long startTime = System.currentTimeMillis();
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(2000);
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));
            in.readLine(); // Should timeout and throw SocketTimeoutException or throw connection closed
        } catch (java.net.SocketException | SocketTimeoutException ex) {
            // Success
        }

        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration < 1500, "Should terminate connection before 1.5s due to proxy timeout");

        transport.stop();
        backend.close();
        t.join();
    }
}
