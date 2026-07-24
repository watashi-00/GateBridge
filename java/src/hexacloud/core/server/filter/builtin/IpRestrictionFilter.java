package hexacloud.core.server.filter.builtin;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.server.filter.*;
import java.io.PrintWriter;

@Order(10)
public class IpRestrictionFilter implements HttpFilter {
    private final Cluster cluster;

    public IpRestrictionFilter(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception {
        if (!cluster.isIpAllowed(request.getClientIp())) {
            response.setStatus(403);
            try (PrintWriter writer = response.getWriter()) {
                writer.print("403 Forbidden - IP Not Allowed");
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
