package hexacloud.core.server.filter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

public class HttpFilterContractsTest {

    @Order
    private static class DefaultOrderedFilter implements HttpFilter {
        @Override
        public void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception {
        }
    }

    @Order(42)
    private static class CustomOrderedFilter implements HttpFilter {
        @Override
        public void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception {
        }
    }

    @Test
    public void testOrderAnnotationDefaultValue() {
        Order annotation = DefaultOrderedFilter.class.getAnnotation(Order.class);
        assertNotNull(annotation);
        assertEquals(100, annotation.value());
    }

    @Test
    public void testOrderAnnotationCustomValue() {
        Order annotation = CustomOrderedFilter.class.getAnnotation(Order.class);
        assertNotNull(annotation);
        assertEquals(42, annotation.value());
    }

    @Test
    public void testHttpFilterInterfaceMethodSignature() throws Exception {
        Method method = HttpFilter.class.getMethod("doFilter", HttpRequest.class, HttpResponse.class, HttpFilterChain.class);
        assertNotNull(method);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    public void testHttpFilterChainInterfaceMethodSignature() throws Exception {
        Method method = HttpFilterChain.class.getMethod("doFilter", HttpRequest.class, HttpResponse.class);
        assertNotNull(method);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    public void testHttpRequestInterfaceMethods() throws Exception {
        assertNotNull(HttpRequest.class.getMethod("getMethod"));
        assertNotNull(HttpRequest.class.getMethod("getRequestURI"));
        assertNotNull(HttpRequest.class.getMethod("getPath"));
        assertNotNull(HttpRequest.class.getMethod("getQuery"));
        assertNotNull(HttpRequest.class.getMethod("getHeader", String.class));
        assertNotNull(HttpRequest.class.getMethod("getHeaders"));
        assertNotNull(HttpRequest.class.getMethod("getClientIp"));
        assertNotNull(HttpRequest.class.getMethod("setAttribute", String.class, Object.class));
        assertNotNull(HttpRequest.class.getMethod("getAttribute", String.class));
    }

    @Test
    public void testHttpResponseInterfaceMethods() throws Exception {
        assertNotNull(HttpResponse.class.getMethod("setHeader", String.class, String.class));
        assertNotNull(HttpResponse.class.getMethod("setStatus", int.class));
        assertNotNull(HttpResponse.class.getMethod("setContentType", String.class));
        assertNotNull(HttpResponse.class.getMethod("getWriter"));
        assertNotNull(HttpResponse.class.getMethod("isCommitted"));
    }
}
