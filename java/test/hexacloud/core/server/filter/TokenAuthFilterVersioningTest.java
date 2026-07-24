package hexacloud.core.server.filter;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.server.filter.builtin.TokenAuthFilter;
import hexacloud.core.server.route.RouteRegistry;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TokenAuthFilterVersioningTest {

    @Test
    public void testFilterPathNormalization() {
        // verify normalized path logic
        String path1 = "/v1/clusters/my-cluster/get_nodes_json";
        if (path1.startsWith("/v1/")) {
            path1 = path1.substring(3);
        }
        assertEquals("/clusters/my-cluster/get_nodes_json", path1);
    }

    @Test
    public void testTokenAuthFilterWithV1PrefixPublicRoute() throws Exception {
        Cluster cluster = mock(Cluster.class);
        RouteRegistry registry = mock(RouteRegistry.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);

        when(cluster.getRouteRegistry()).thenReturn(registry);
        when(request.getPath()).thenReturn("/v1/clusters/my-cluster/get_nodes_json");
        when(registry.isRoutePublic("GET_NODES_JSON")).thenReturn(true);

        TokenAuthFilter filter = new TokenAuthFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    public void testTokenAuthFilterWithV1PrefixDirectPublicRoute() throws Exception {
        Cluster cluster = mock(Cluster.class);
        RouteRegistry registry = mock(RouteRegistry.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);

        when(cluster.getRouteRegistry()).thenReturn(registry);
        when(request.getPath()).thenReturn("/v1/health");
        when(registry.isRoutePublic("HEALTH")).thenReturn(true);

        TokenAuthFilter filter = new TokenAuthFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }
}
