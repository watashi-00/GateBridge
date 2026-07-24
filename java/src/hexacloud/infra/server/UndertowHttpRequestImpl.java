package hexacloud.infra.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import hexacloud.core.server.filter.HttpRequest;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class UndertowHttpRequestImpl implements HttpRequest {

    private final HttpServerExchange exchange;
    private Map<String, Object> attributes;
    private URI requestUri;

    public UndertowHttpRequestImpl(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    private String method;

    @Override
    public String getMethod() {
        if (method == null) {
            io.undertow.util.HttpString requestMethod = exchange.getRequestMethod();
            if (io.undertow.util.Methods.GET.equals(requestMethod)) {
                method = "GET";
            } else if (io.undertow.util.Methods.POST.equals(requestMethod)) {
                method = "POST";
            } else {
                method = requestMethod.toString();
            }
        }
        return method;
    }

    @Override
    public URI getRequestURI() {
        if (requestUri == null) {
            String query = exchange.getQueryString();
            String url = exchange.getRequestURL();
            if (query != null && !query.isEmpty()) {
                this.requestUri = URI.create(url + "?" + query);
            } else {
                this.requestUri = URI.create(url);
            }
        }
        return requestUri;
    }

    @Override
    public String getPath() {
        return exchange.getRequestPath();
    }

    @Override
    public String getQuery() {
        return exchange.getQueryString();
    }

    @Override
    public String getHeader(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        Map<String, List<String>> map = new HashMap<>();
        for (HeaderValues hv : exchange.getRequestHeaders()) {
            List<String> values = new ArrayList<>();
            for (String v : hv) {
                values.add(v);
            }
            map.put(hv.getHeaderName().toString(), values);
        }
        return map;
    }

    @Override
    public String getClientIp() {
        if (exchange.getSourceAddress() != null && exchange.getSourceAddress().getAddress() != null) {
            return exchange.getSourceAddress().getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    @Override
    public Object getAttribute(String key) {
        return attributes == null ? null : attributes.get(key);
    }
}
