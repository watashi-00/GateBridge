package hexacloud.infra.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.cluster.event.ClusterEvent;
import hexacloud.core.event.EventBusManager;
import hexacloud.core.event.EventFormat;
import hexacloud.core.model.PingProtocol;
import hexacloud.core.server.route.RouteRegistry;

public class WsTransportTest {

    @Test
    public void testWebSocketHandshakeAndEventStream() throws Exception {
        int port = findFreePort();
        WsTransport transport = new WsTransport();
        transport.listen(port, new RouteRegistry(), new Cluster("ws-test-cluster"), new java.util.ArrayList<>());

        waitUntilRunning(transport);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(("GET / HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String response = readHttpResponse(in);
            assertTrue(response.startsWith("HTTP/1.1 101"));

            String connectedFrame = readTextFrame(in);
            assertTrue(connectedFrame.contains("\"type\":\"Connected\""));

            EventBusManager.getGlobal().dispatch(new ClusterEvent.NodeTelemetryUpdated("http://127.0.0.1:7001"));
            String eventFrame = readTextFrame(in);
            assertTrue(eventFrame.contains("\"type\":\"NodeTelemetryUpdated\""));
            assertTrue(eventFrame.contains("http://127.0.0.1:7001"));

            EventBusManager.getGlobal().dispatch(new ClusterEvent.NodeEventSubmitted(
                "http://127.0.0.1:7001",
                7001,
                PingProtocol.HTTP,
                EventFormat.JSON,
                "cache.warmed",
                Collections.singletonMap("detail", "products")
            ));
            String submittedFrame = readTextFrame(in);
            assertTrue(submittedFrame.contains("\"type\":\"NodeEventSubmitted\""));
            assertTrue(submittedFrame.contains("\"protocol\":\"HTTP\""));
            assertTrue(submittedFrame.contains("\"format\":\"json\""));
            assertTrue(submittedFrame.contains("\"event\":\"cache.warmed\""));
            assertTrue(submittedFrame.contains("\"detail\":\"products\""));
        } finally {
            transport.stop();
        }
    }

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitUntilRunning(WsTransport transport) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (!transport.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(transport.isRunning());
    }

    private String readHttpResponse(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            buffer.write(current);
            String response = buffer.toString(StandardCharsets.US_ASCII);
            if (previous == '\r' && current == '\n' && response.endsWith("\r\n\r\n")) {
                return response;
            }
            previous = current;
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private String readTextFrame(InputStream in) throws Exception {
        int first = in.read();
        int second = in.read();
        assertEquals(0x81, first);

        int length = second & 0x7f;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            long longLength = 0;
            for (int i = 0; i < 8; i++) {
                longLength = (longLength << 8) | in.read();
            }
            length = (int) longLength;
        }

        byte[] payload = in.readNBytes(length);
        assertEquals(length, payload.length);
        return new String(payload, StandardCharsets.UTF_8);
    }
}
