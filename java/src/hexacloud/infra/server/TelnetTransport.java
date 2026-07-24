package hexacloud.infra.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.List;
import hexacloud.core.server.filter.HttpFilter;

import hexacloud.core.server.ServerTransport;
import hexacloud.core.server.route.RouteRegistry;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.concurrent.ThreadManager;

/**
 * Concrete Telnet implementation of ServerTransport bound to a local port
 * and using virtual threads for socket connection handling and command routing.
 */
public class TelnetTransport implements ServerTransport {

    private boolean clusterActive = true;
    private ServerSocket serverSocket;
    private boolean running = false;
    private final ExecutorService threadPool = ThreadManager.newVirtualThreadPool();

    @Override
    public void listen(int port, RouteRegistry registry, hexacloud.core.cluster.Cluster cluster, List<HttpFilter> customFilters) {
        new Thread(() -> serverListen(port, registry, cluster), "TelnetServer-Listener-" + port).start();
    }

    private void serverListen(int port, RouteRegistry registry, hexacloud.core.cluster.Cluster cluster) {
        DebugUtils.log("Telnet Transport starting to listen on port " + port);
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            DebugUtils.info("Telnet Transport successfully bound and listening on port " + port);
            while(clusterActive) {
                Socket socket = serverSocket.accept();
                DebugUtils.log("Telnet Transport accepted new connection from " + socket.getRemoteSocketAddress());
                threadPool.execute(() -> conn(socket, registry, cluster));
            }
        } catch(IOException ex) {
            if (clusterActive) {
                DebugUtils.error("Telnet Transport failed to open port " + port, ex);
            }
        }
    }

    private void conn(Socket socket, RouteRegistry registry, hexacloud.core.cluster.Cluster cluster) {
        try{
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            String clientIp = socket.getInetAddress().getHostAddress();

            String line = in.readLine();
            if(line == null || line.trim().isEmpty()) {
                DebugUtils.log("Telnet received empty connection request from " + socket.getRemoteSocketAddress());
                return;
            }

            DebugUtils.log("Telnet received raw command line: '" + line + "'");

            String[] tokens = line.split(" ", 3);
            String command;
            String args;

            hexacloud.core.cluster.Cluster targetCluster = cluster;
            RouteRegistry targetRegistry = registry;

            if(tokens.length >= 2) {
                String token = tokens[0];
                for (hexacloud.core.cluster.Cluster c : hexacloud.core.cluster.ClusterRegistry.getInstance().getClusters()) {
                    if (token.equals(c.getSecret())) {
                        targetCluster = c;
                        targetRegistry = c.getRouteRegistry();
                        break;
                    }
                }
            }

            if(!targetCluster.isIpAllowed(clientIp)) {
                out.println("ERROR: IP not allowed");
                return;
            }

            if(!targetCluster.checkRateLimit(clientIp)) {
                out.println("ERROR: Too many requests");
                return;
            }

            if(targetCluster.isRequireToken()) {
                if(tokens.length < 2) {
                    out.println("ERROR: Authentication required");
                    return;
                }
                String token = tokens[0];
                if(!targetCluster.authenticate(token)) {
                    out.println("ERROR: Unauthorized");
                    return;
                }
                command = tokens[1].toUpperCase();
                args = tokens.length > 2 ? tokens[2].trim() : "";
            } else {
                command = tokens[0].toUpperCase();
                String firstTokenArgs = tokens.length > 1 ? tokens[1].trim() : "";
                args = tokens.length > 2 ? (firstTokenArgs + " " + tokens[2].trim()).trim() : firstTokenArgs;
            }

            BiConsumer<String, PrintWriter> handler = targetRegistry.getRoutes().get(command);

            if(handler == null) {
                DebugUtils.error("Telnet: Unknown command '" + command + "' received from client.");
                out.println("Unknown command " + command);
                return;
            }

            DebugUtils.log("Telnet: Executing route handler for command '" + command + "' with args '" + args + "'");
            handler.accept(args, out);
            DebugUtils.log("Telnet: Successfully completed request handler for command '" + command + "'");
            
        } catch(IOException ex) {
            DebugUtils.error("Failed to process request from client", ex);
        } finally {
            try {
                socket.close();
            } catch(IOException ex) {
                DebugUtils.error("Failed to close client socket", ex);
            }
        }
    }

    @Override
    public void stop() {
        clusterActive = false;
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                DebugUtils.error("Error closing Telnet server socket", e);
            }
        }
        threadPool.shutdownNow();
        DebugUtils.log("Telnet Transport stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
