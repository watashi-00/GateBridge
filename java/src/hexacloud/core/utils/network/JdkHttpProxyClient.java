package hexacloud.core.utils.network;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;

public class JdkHttpProxyClient implements HttpProxyClient {
    private final HttpClient client;

    public JdkHttpProxyClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ProxyResponse execute(String targetUrl, String method, Map<String, List<String>> headers, InputStream body, int timeoutMs) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null 
                ? HttpRequest.BodyPublishers.noBody() 
                : HttpRequest.BodyPublishers.ofInputStream(() -> body);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .method(method, publisher)
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 10000));

        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Content-Length") || key.equalsIgnoreCase("Connection")) {
                    continue;
                }
                for (String val : entry.getValue()) {
                    if (val != null) {
                        builder.header(key, val);
                    }
                }
            }
        }

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new ProxyResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
