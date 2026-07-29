package hexacloud.core.server.filter.builtin;

import hexacloud.core.server.filter.HttpFilter;
import hexacloud.core.server.filter.HttpFilterChain;
import hexacloud.core.server.filter.HttpRequest;
import hexacloud.core.server.filter.HttpResponse;

public class CorsFilter implements HttpFilter {

    @Override
    public void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        response.setHeader("Access-Control-Allow-Headers", "X-Cluster-Token, Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(204);
            return; // Short-circuit preflight
        }

        chain.doFilter(request, response);
    }
}
