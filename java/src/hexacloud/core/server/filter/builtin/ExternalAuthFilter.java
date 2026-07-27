package hexacloud.core.server.filter.builtin;

import hexacloud.core.server.filter.*;
import hexacloud.core.utils.common.DebugUtils;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ExternalAuthFilter — delegates authentication to an external HTTP auth service.
 *
 * <p>Inspired by Nginx's {@code auth_request} directive. For every incoming request,
 * this filter calls the configured {@code authServiceUrl} forwarding relevant headers
 * (Authorization, X-Cluster-Token, X-Real-IP, X-Forwarded-For, Cookie).
 * The auth service must return:</p>
 * <ul>
 *   <li>2xx — authentication granted, request continues the filter chain.</li>
 *   <li>401/403 — authentication denied, gateway returns 401 to the client.</li>
 *   <li>Any other error / timeout — gateway returns 502 to the client.</li>
 * </ul>
 *
 * <p>Usage (fluent builder):</p>
 * <pre>
 *   gateway.authService("http://auth-service:9000/verify")
 * </pre>
 */
@Order(20)
public class ExternalAuthFilter implements HttpFilter {

    private static final int DEFAULT_TIMEOUT_MS = 3000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
            .build();

    private final String authServiceUrl;
    private final int timeoutMs;

    /**
     * @param authServiceUrl Full URL of the auth endpoint (e.g., "http://auth:9000/verify").
     * @param timeoutMs      Request timeout in milliseconds.
     */
    public ExternalAuthFilter(String authServiceUrl, int timeoutMs) {
        this.authServiceUrl = authServiceUrl;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public ExternalAuthFilter(String authServiceUrl) {
        this(authServiceUrl, DEFAULT_TIMEOUT_MS);
    }

    @Override
    public void doFilter(hexacloud.core.server.filter.HttpRequest request, hexacloud.core.server.filter.HttpResponse response, HttpFilterChain chain) throws Exception {
        HttpRequest.Builder authRequest = HttpRequest.newBuilder()
                .uri(URI.create(authServiceUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();

        // Forward standard auth-related headers to the auth service
        forwardHeader(request, authRequest, "Authorization");
        forwardHeader(request, authRequest, "X-Cluster-Token");
        forwardHeader(request, authRequest, "X-Real-IP");
        forwardHeader(request, authRequest, "X-Forwarded-For");
        forwardHeader(request, authRequest, "Cookie");
        // Forward original request path so the auth service can apply path-based rules
        authRequest.header("X-Original-URI", request.getPath());

        int statusCode;
        try {
            HttpResponse<Void> authResponse = HTTP_CLIENT.send(
                    authRequest.build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            statusCode = authResponse.statusCode();
        } catch (Exception ex) {
            DebugUtils.error("ExternalAuthFilter: auth service call failed for " + authServiceUrl, ex);
            response.setStatus(502);
            try (PrintWriter writer = response.getWriter()) {
                writer.print("502 Bad Gateway - Auth service unavailable");
            }
            return;
        }

        if (statusCode >= 200 && statusCode < 300) {
            // Auth granted
            chain.doFilter(request, response);
        } else if (statusCode == 401 || statusCode == 403) {
            response.setStatus(401);
            try (PrintWriter writer = response.getWriter()) {
                writer.print("401 Unauthorized - Auth service denied access");
            }
        } else {
            DebugUtils.error("ExternalAuthFilter: unexpected auth service response: " + statusCode + " from " + authServiceUrl, null);
            response.setStatus(502);
            try (PrintWriter writer = response.getWriter()) {
                writer.print("502 Bad Gateway - Unexpected auth service response");
            }
        }
    }

    private void forwardHeader(hexacloud.core.server.filter.HttpRequest request, HttpRequest.Builder builder, String headerName) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isEmpty()) {
            builder.header(headerName, value);
        }
    }

    public String getAuthServiceUrl() {
        return authServiceUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }
}
