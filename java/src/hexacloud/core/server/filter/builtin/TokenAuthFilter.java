package hexacloud.core.server.filter.builtin;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.server.filter.*;
import java.io.PrintWriter;

@Order(30)
public class TokenAuthFilter implements HttpFilter {
    private final Cluster cluster;

    public TokenAuthFilter(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path != null) {
            if (path.startsWith("/v1/")) {
                path = path.substring(3);
            } else if (path.equals("/v1")) {
                path = "/";
            }
        }
        String routeName = "";
        if (path != null) {
            if (path.startsWith("/clusters/")) {
                String pathWithoutClusters = path.substring("/clusters/".length());
                int slashIdx = pathWithoutClusters.indexOf('/');
                if (slashIdx != -1) {
                    String clusterSubpath = pathWithoutClusters.substring(slashIdx);
                    routeName = clusterSubpath.length() > 1 ? clusterSubpath.substring(1).toUpperCase() : "";
                }
            } else {
                routeName = path.length() > 1 ? path.substring(1).toUpperCase() : "";
            }
        }
        if (cluster.getRouteRegistry() != null && cluster.getRouteRegistry().isRoutePublic(routeName)) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader("X-Cluster-Token");
        if (token == null || token.isEmpty()) {
            String query = request.getQuery();
            if (query != null && query.contains("token=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) {
                        token = param.substring(6);
                        break;
                    }
                }
            }
        }

        if (!cluster.authenticate(token)) {
            response.setHeader("Connection", "close");
            response.setStatus(401);
            try (PrintWriter writer = response.getWriter()) {
                writer.print("401 Unauthorized - Invalid or Missing API Token");
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
