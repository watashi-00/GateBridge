package hexacloud.core.server.filter;

import java.net.URI;
import java.util.Map;
import java.util.List;

public interface HttpRequest {
    String getMethod();
    URI getRequestURI();
    String getPath();
    String getQuery();
    String getHeader(String name);
    Map<String, List<String>> getHeaders();
    String getClientIp();
    void setAttribute(String key, Object value);
    Object getAttribute(String key);
}
