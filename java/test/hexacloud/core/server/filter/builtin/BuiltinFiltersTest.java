package hexacloud.core.server.filter.builtin;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.server.filter.HttpFilterChain;
import hexacloud.core.server.filter.HttpRequest;
import hexacloud.core.server.filter.HttpResponse;
import hexacloud.core.server.filter.Order;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class BuiltinFiltersTest {

    @Test
    public void testBuiltinExistenceAndAnnotations() {
        assertNotNull(IpRestrictionFilter.class);
        assertNotNull(RateLimitFilter.class);
        assertNotNull(TokenAuthFilter.class);

        Order ipOrder = IpRestrictionFilter.class.getAnnotation(Order.class);
        assertNotNull(ipOrder);
        assertEquals(10, ipOrder.value());

        Order rateOrder = RateLimitFilter.class.getAnnotation(Order.class);
        assertNotNull(rateOrder);
        assertEquals(20, rateOrder.value());

        Order tokenOrder = TokenAuthFilter.class.getAnnotation(Order.class);
        assertNotNull(tokenOrder);
        assertEquals(30, tokenOrder.value());
    }

    @Test
    public void testIpRestrictionFilterAllowed() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        when(request.getClientIp()).thenReturn("127.0.0.1");
        when(cluster.isIpAllowed("127.0.0.1")).thenReturn(true);

        IpRestrictionFilter filter = new IpRestrictionFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(403);
    }

    @Test
    public void testIpRestrictionFilterBlocked() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        when(request.getClientIp()).thenReturn("10.0.0.1");
        when(cluster.isIpAllowed("10.0.0.1")).thenReturn(false);
        when(response.getWriter()).thenReturn(pw);

        IpRestrictionFilter filter = new IpRestrictionFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(403);
        assertTrue(sw.toString().contains("403 Forbidden - IP Not Allowed"));
    }

    @Test
    public void testRateLimitFilterAllowed() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        when(request.getClientIp()).thenReturn("127.0.0.1");
        when(cluster.checkRateLimit("127.0.0.1")).thenReturn(true);

        RateLimitFilter filter = new RateLimitFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    public void testRateLimitFilterExceeded() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        when(request.getClientIp()).thenReturn("10.0.0.1");
        when(cluster.checkRateLimit("10.0.0.1")).thenReturn(false);
        when(response.getWriter()).thenReturn(pw);

        RateLimitFilter filter = new RateLimitFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setHeader("Connection", "close");
        verify(response, times(1)).setHeader("Retry-After", "10");
        verify(response, times(1)).setStatus(429);
        assertTrue(sw.toString().contains("429 Too Many Requests"));
    }

    @Test
    public void testTokenAuthFilterHeaderSuccess() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        when(request.getHeader("X-Cluster-Token")).thenReturn("secret-token-123");
        when(cluster.authenticate("secret-token-123")).thenReturn(true);

        TokenAuthFilter filter = new TokenAuthFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    public void testTokenAuthFilterQueryParamSuccess() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        when(request.getHeader("X-Cluster-Token")).thenReturn(null);
        when(request.getQuery()).thenReturn("user=admin&token=query-token-456&debug=true");
        when(cluster.authenticate("query-token-456")).thenReturn(true);

        TokenAuthFilter filter = new TokenAuthFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    public void testTokenAuthFilterFailure() throws Exception {
        Cluster cluster = mock(Cluster.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        HttpFilterChain chain = mock(HttpFilterChain.class);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        when(request.getHeader("X-Cluster-Token")).thenReturn(null);
        when(request.getQuery()).thenReturn("user=admin");
        when(cluster.authenticate(null)).thenReturn(false);
        when(response.getWriter()).thenReturn(pw);

        TokenAuthFilter filter = new TokenAuthFilter(cluster);
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setHeader("Connection", "close");
        verify(response, times(1)).setStatus(401);
        assertTrue(sw.toString().contains("401 Unauthorized - Invalid or Missing API Token"));
    }
}
