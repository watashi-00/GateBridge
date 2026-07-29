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
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public ProxyResponse execute(String targetUrl, String method, Map<String, List<String>> headers, InputStream body, int timeoutMs) throws Exception {
        boolean hasBody = false;
        if (headers != null) {
            List<String> contentLengths = headers.get("Content-Length");
            if (contentLengths == null || contentLengths.isEmpty()) {
                // Try case-insensitive lookup
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    if ("Content-Length".equalsIgnoreCase(entry.getKey())) {
                        contentLengths = entry.getValue();
                        break;
                    }
                }
            }
            if (contentLengths != null && !contentLengths.isEmpty()) {
                try {
                    long len = Long.parseLong(contentLengths.get(0).trim());
                    hasBody = len > 0;
                } catch (Exception ignored) {}
            } else {
                List<String> transferEncodings = headers.get("Transfer-Encoding");
                if (transferEncodings == null || transferEncodings.isEmpty()) {
                    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                        if ("Transfer-Encoding".equalsIgnoreCase(entry.getKey())) {
                            transferEncodings = entry.getValue();
                            break;
                        }
                    }
                }
                if (transferEncodings != null && !transferEncodings.isEmpty()) {
                    hasBody = true;
                }
            }
        }
        if (!hasBody) {
            hasBody = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
        }

        HttpRequest.BodyPublisher publisher = (hasBody && body != null)
                ? HttpRequest.BodyPublishers.ofInputStream(() -> body)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .method(method, publisher)
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 10000));

        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Content-Length") 
                        || key.equalsIgnoreCase("Connection") || key.equalsIgnoreCase("Upgrade")
                        || key.equalsIgnoreCase("Transfer-Encoding") || key.equalsIgnoreCase("Keep-Alive")
                        || key.equalsIgnoreCase("Proxy-Connection")) {
                    continue;
                }
                if (key.equalsIgnoreCase("X-Forwarded-For")) {
                    key = "X-Forwarded-For";
                } else if (key.equalsIgnoreCase("X-Forwarded-Host")) {
                    key = "X-Forwarded-Host";
                } else if (key.equalsIgnoreCase("X-Forwarded-Proto")) {
                    key = "X-Forwarded-Proto";
                } else if (key.equalsIgnoreCase("Content-Type")) {
                    key = "Content-Type";
                }
                if (!entry.getValue().isEmpty()) {
                    builder.header(key, String.join(", ", entry.getValue()));
                }
            }
        }

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new ProxyResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
