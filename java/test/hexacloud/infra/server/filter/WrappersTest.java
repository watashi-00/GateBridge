package hexacloud.infra.server.filter;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class WrappersTest {

    @Test
    public void testAttributesLifecycle() {
        HttpRequestImpl request = new HttpRequestImpl(null);
        request.setAttribute("test-key", "test-val");
        assertEquals("test-val", request.getAttribute("test-key"));
        assertNull(request.getAttribute("non-existent"));
    }

    @Test
    public void testHttpRequestDelegation() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers headers = new Headers();
        headers.put("Authorization", Collections.singletonList("Bearer token123"));

        URI uri = new URI("http://localhost:8080/api/v1/users?query=test");
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 12345);

        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(uri);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getRemoteAddress()).thenReturn(remoteAddress);

        HttpRequestImpl request = new HttpRequestImpl(exchange);

        assertEquals("GET", request.getMethod());
        assertEquals(uri, request.getRequestURI());
        assertEquals("/api/v1/users", request.getPath());
        assertEquals("query=test", request.getQuery());
        assertEquals("Bearer token123", request.getHeader("Authorization"));
        assertEquals(headers, request.getHeaders());
        assertEquals("127.0.0.1", request.getClientIp());
    }

    @Test
    public void testHttpResponseDelegation() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers responseHeaders = new Headers();
        OutputStream responseBody = new ByteArrayOutputStream();

        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.getResponseBody()).thenReturn(responseBody);

        HttpResponseImpl response = new HttpResponseImpl(exchange);

        assertFalse(response.isCommitted());

        response.setHeader("X-Custom-Header", "Value");
        assertEquals("Value", responseHeaders.getFirst("X-Custom-Header"));

        response.setContentType("application/json");
        assertEquals("application/json", responseHeaders.getFirst("Content-Type"));

        response.setStatus(200);
        assertTrue(response.isCommitted());
        verify(exchange).sendResponseHeaders(200, 0);

        var writer = response.getWriter();
        assertNotNull(writer);
        assertTrue(response.isCommitted());

        writer.println("hello world");
        // Due to BufferedWriter without auto-flush, responseBody is buffered until flushed
        writer.flush();
        assertEquals("hello world" + System.lineSeparator(), responseBody.toString());
    }
}
