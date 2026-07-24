package hexacloud.core.server.filter;

public interface HttpFilter {
    void doFilter(HttpRequest request, HttpResponse response, HttpFilterChain chain) throws Exception;
}
