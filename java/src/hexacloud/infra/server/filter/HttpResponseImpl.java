package hexacloud.infra.server.filter;

import com.sun.net.httpserver.HttpExchange;
import hexacloud.core.server.filter.HttpResponse;
import java.io.PrintWriter;

public class HttpResponseImpl implements HttpResponse {
    private final HttpExchange exchange;
    private boolean committed = false;

    public HttpResponseImpl(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void setHeader(String name, String value) {
        exchange.getResponseHeaders().set(name, value);
    }

    @Override
    public void setStatus(int statusCode) {
        if (committed) return;
        try {
            exchange.sendResponseHeaders(statusCode, 0);
            committed = true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setContentType(String contentType) {
        setHeader("Content-Type", contentType);
    }

    @Override
    public PrintWriter getWriter() throws Exception {
        if (!committed) {
            setStatus(200);
        }
        return new PrintWriter(new java.io.BufferedWriter(new java.io.OutputStreamWriter(exchange.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }
}
