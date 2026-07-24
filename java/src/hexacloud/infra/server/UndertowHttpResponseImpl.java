package hexacloud.infra.server;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import io.undertow.util.Headers;
import hexacloud.core.server.filter.HttpResponse;
import java.io.PrintWriter;

public class UndertowHttpResponseImpl implements HttpResponse {

    private final HttpServerExchange exchange;
    private PrintWriter writer;
    private boolean statusSet = false;

    public UndertowHttpResponseImpl(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void setHeader(String name, String value) {
        exchange.getResponseHeaders().put(HttpString.tryFromString(name), value);
    }

    @Override
    public void setStatus(int statusCode) {
        exchange.setStatusCode(statusCode);
        statusSet = true;
    }

    @Override
    public void setContentType(String contentType) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
    }

    @Override
    public PrintWriter getWriter() throws Exception {
        if (writer == null) {
            if (!statusSet && !exchange.isResponseStarted()) {
                exchange.setStatusCode(200);
            }
            if (!exchange.isBlocking()) {
                exchange.startBlocking();
            }
            writer = new PrintWriter(new java.io.BufferedWriter(new java.io.OutputStreamWriter(exchange.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)));
        }
        return writer;
    }

    @Override
    public boolean isCommitted() {
        return exchange.isResponseStarted();
    }
    
    public void flushBuffer() {
        if (writer != null) {
            writer.flush();
        }
    }
}
