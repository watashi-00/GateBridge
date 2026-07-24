package hexacloud.infra.server.filter;

import com.sun.net.httpserver.HttpExchange;
import hexacloud.core.server.filter.HttpRequest;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class HttpRequestImpl implements HttpRequest {
    private final HttpExchange exchange;
    private Map<String, Object> attributes;

    public HttpRequestImpl(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override public String getMethod() { return exchange.getRequestMethod(); }
    @Override public URI getRequestURI() { return exchange.getRequestURI(); }
    @Override public String getPath() { return exchange.getRequestURI().getPath(); }
    @Override public String getQuery() { return exchange.getRequestURI().getQuery(); }
    @Override public String getHeader(String name) { return exchange.getRequestHeaders().getFirst(name); }
    @Override public Map<String, List<String>> getHeaders() { return exchange.getRequestHeaders(); }
    @Override public String getClientIp() {
        if (exchange.getRemoteAddress() != null) {
            if (exchange.getRemoteAddress().getAddress() != null) {
                return exchange.getRemoteAddress().getAddress().getHostAddress();
            }
            return exchange.getRemoteAddress().getHostString();
        }
        return "127.0.0.1";
    }
    @Override public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }
    @Override public Object getAttribute(String key) {
        return attributes == null ? null : attributes.get(key);
    }
}
