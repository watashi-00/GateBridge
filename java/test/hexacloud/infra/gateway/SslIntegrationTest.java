package hexacloud.infra.gateway;

import static org.junit.jupiter.api.Assertions.*;

import hexacloud.core.ports.SslContextPort;
import hexacloud.core.server.ServerManager;
import hexacloud.infra.server.HttpTransport;
import hexacloud.infra.server.UndertowHttpTransport;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.io.IOException;
import java.util.List;

public class SslIntegrationTest {

    private static class DummySslContextPort implements SslContextPort {
        private final SSLContext context;
        private final int port;
        private final boolean enabled;

        public DummySslContextPort(SSLContext context, int port, boolean enabled) {
            this.context = context;
            this.port = port;
            this.enabled = enabled;
        }

        @Override
        public SSLContext getSslContext() {
            return context;
        }

        @Override
        public int getSslPort() {
            return port;
        }

        @Override
        public boolean isSslEnabled() {
            return enabled;
        }

        @Override
        public void reload() {}
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    @Test
    public void testSslContextPropagationJdkEngine() throws Exception {
        SSLContext dummyContext = SSLContext.getDefault();
        int sslPort = findFreePort();
        SslContextPort sslContextPort = new DummySslContextPort(dummyContext, sslPort, true);

        LocalGatewayAdapter gateway = (LocalGatewayAdapter) GatewayFactory.createGateway("ssl-test-cluster-jdk");
        gateway.sslContext(sslContextPort)
               .httpEngine(hexacloud.core.server.HttpEngine.JDK_DEFAULT)
               .enableHttp(true);

        Field serverManagerField = LocalGatewayAdapter.class.getDeclaredField("serverManager");
        serverManagerField.setAccessible(true);
        ServerManager serverManager = (ServerManager) serverManagerField.get(gateway);
        assertNotNull(serverManager);

        int basePort = findFreePort();
        try {
            gateway.listen(basePort);
            assertEquals(sslContextPort, serverManager.getSslContext());

            // Check active transports list via reflection
            Field activeTransportsField = ServerManager.class.getDeclaredField("activeTransports");
            activeTransportsField.setAccessible(true);
            List<?> activeTransports = (List<?>) activeTransportsField.get(serverManager);
            assertFalse(activeTransports.isEmpty());

            boolean foundHttpTransport = false;
            for (Object transport : activeTransports) {
                if (transport instanceof HttpTransport) {
                    foundHttpTransport = true;
                    Field sslContextPortField = HttpTransport.class.getDeclaredField("sslContextPort");
                    sslContextPortField.setAccessible(true);
                    assertEquals(sslContextPort, sslContextPortField.get(transport));
                }
            }
            assertTrue(foundHttpTransport, "JDK HttpTransport should be active and configured");
        } finally {
            gateway.stop();
        }
    }

    @Test
    public void testSslContextPropagationUndertowEngine() throws Exception {
        SSLContext dummyContext = SSLContext.getDefault();
        int sslPort = findFreePort();
        SslContextPort sslContextPort = new DummySslContextPort(dummyContext, sslPort, true);

        LocalGatewayAdapter gateway = (LocalGatewayAdapter) GatewayFactory.createGateway("ssl-test-cluster-undertow");
        gateway.sslContext(sslContextPort)
               .httpEngine(hexacloud.core.server.HttpEngine.UNDERTOW)
               .enableHttp(true);

        Field serverManagerField = LocalGatewayAdapter.class.getDeclaredField("serverManager");
        serverManagerField.setAccessible(true);
        ServerManager serverManager = (ServerManager) serverManagerField.get(gateway);
        assertNotNull(serverManager);

        int basePort = findFreePort();
        try {
            gateway.listen(basePort);
            assertEquals(sslContextPort, serverManager.getSslContext());

            // Check active transports list via reflection
            Field activeTransportsField = ServerManager.class.getDeclaredField("activeTransports");
            activeTransportsField.setAccessible(true);
            List<?> activeTransports = (List<?>) activeTransportsField.get(serverManager);
            assertFalse(activeTransports.isEmpty());

            boolean foundUndertowTransport = false;
            for (Object transport : activeTransports) {
                if (transport instanceof UndertowHttpTransport) {
                    foundUndertowTransport = true;
                    Field sslContextPortField = UndertowHttpTransport.class.getDeclaredField("sslContextPort");
                    sslContextPortField.setAccessible(true);
                    assertEquals(sslContextPort, sslContextPortField.get(transport));
                }
            }
            assertTrue(foundUndertowTransport, "UndertowHttpTransport should be active and configured");
        } finally {
            gateway.stop();
        }
    }
}
