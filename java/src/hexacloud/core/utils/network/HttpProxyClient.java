package hexacloud.core.utils.network;

import java.io.InputStream;
import java.util.Map;
import java.util.List;

public interface HttpProxyClient {
    ProxyResponse execute(String targetUrl, String method, Map<String, List<String>> headers, InputStream body, int timeoutMs) throws Exception;
}
