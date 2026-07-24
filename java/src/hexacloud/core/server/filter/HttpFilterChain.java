package hexacloud.core.server.filter;

public interface HttpFilterChain {
    void doFilter(HttpRequest request, HttpResponse response) throws Exception;
}
