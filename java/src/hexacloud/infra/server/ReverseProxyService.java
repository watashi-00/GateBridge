package hexacloud.infra.server;

import hexacloud.core.cluster.Cluster;
import hexacloud.core.model.ServerNode;
import hexacloud.core.server.filter.HttpRequest;
import hexacloud.core.server.filter.HttpResponse;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.network.HttpProxyClient;
import hexacloud.core.utils.network.JdkHttpProxyClient;
import hexacloud.core.utils.network.ProxyResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReverseProxyService {
    private final HttpProxyClient proxyClient;
    private final HttpErrorHandler errorHandler;

    public ReverseProxyService(HttpProxyClient proxyClient, HttpErrorHandler errorHandler) {
        this.proxyClient = proxyClient != null ? proxyClient : new JdkHttpProxyClient();
        this.errorHandler = errorHandler != null ? errorHandler : new DefaultHttpErrorHandler();
    }

    public void proxyRequest(HttpRequest req, HttpResponse res, Cluster targetCluster, String subpath, int timeoutMs) {
        ServerNode targetNode = targetCluster.selectNode();
        if (targetNode == null) {
            errorHandler.handleStatus(res, 503, "No active nodes in cluster: " + targetCluster.getClusterName());
            return;
        }

        String targetUrl = targetNode.getFullHost() + (subpath.startsWith("/") ? subpath : "/" + subpath);
        String query = req.getQuery();
        if (query != null && !query.isEmpty()) {
            targetUrl += "?" + query;
        }

        Map<String, List<String>> headers = new HashMap<>();
        if (req.getHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : req.getHeaders().entrySet()) {
                headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        
        // Add traceability headers
        String clientIp = req.getClientIp();
        if (clientIp != null) {
            headers.computeIfAbsent("X-Forwarded-For", k -> new ArrayList<>()).add(clientIp);
        }
        headers.computeIfAbsent("X-Forwarded-Host", k -> new ArrayList<>()).add(req.getHeader("Host"));
        headers.computeIfAbsent("X-Forwarded-Proto", k -> new ArrayList<>()).add("http");

        try (InputStream bodyIn = req.getBody()) {
            ProxyResponse response = proxyClient.execute(targetUrl, req.getMethod(), headers, bodyIn, timeoutMs);
            
            res.setStatus(response.statusCode());
            
            // Forward headers
            for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.equalsIgnoreCase("Transfer-Encoding") || key.equalsIgnoreCase("Content-Length")) {
                    continue;
                }
                for (String val : entry.getValue()) {
                    res.setHeader(key, val);
                }
            }

            try (InputStream in = response.bodyStream(); OutputStream out = res.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
        } catch (Exception e) {
            DebugUtils.error("ReverseProxyService: Proxy request failed to " + targetUrl, e);
            errorHandler.handleException(res, e);
        }
    }
}
