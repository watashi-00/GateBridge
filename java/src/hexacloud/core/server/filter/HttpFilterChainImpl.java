package hexacloud.core.server.filter;

import java.util.List;
import java.util.function.BiConsumer;

public class HttpFilterChainImpl implements HttpFilterChain {
    private final List<HttpFilter> filters;
    private final BiConsumer<HttpRequest, HttpResponse> routeHandler;
    private int index = 0;

    public HttpFilterChainImpl(List<HttpFilter> filters, BiConsumer<HttpRequest, HttpResponse> routeHandler) {
        this.filters = filters;
        this.routeHandler = routeHandler;
    }

    @Override
    public void doFilter(HttpRequest request, HttpResponse response) throws Exception {
        if (index < filters.size()) {
            HttpFilter filter = filters.get(index++);
            filter.doFilter(request, response, this);
        } else {
            routeHandler.accept(request, response);
        }
    }
}
