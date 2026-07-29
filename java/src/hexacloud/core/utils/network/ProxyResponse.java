package hexacloud.core.utils.network;

import java.io.InputStream;
import java.util.Map;
import java.util.List;

public class ProxyResponse {
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final InputStream bodyStream;

    public ProxyResponse(int statusCode, Map<String, List<String>> headers, InputStream bodyStream) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.bodyStream = bodyStream;
    }

    public int statusCode() { return statusCode; }
    public Map<String, List<String>> headers() { return headers; }
    public InputStream bodyStream() { return bodyStream; }
}
