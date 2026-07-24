package hexacloud.core.server.filter.builtin;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.server.filter.*;
import java.io.PrintWriter;

@Order(20)
public class RateLimitFilter implements HttpFilter {
    private final Cluster cluster;

    public RateLimitFilter(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception {
        if (!cluster.checkRateLimit(request.getClientIp())) {
            response.setHeader("Connection", "close");
            response.setHeader("Retry-After", "10");
            response.setStatus(429);
            try (PrintWriter writer = response.getWriter()) {
                writer.print("429 Too Many Requests");
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
