package hexacloud.core.utils.network;

import java.net.http.HttpRequest.Builder;
import hexacloud.core.server.filter.HttpRequest;

public class HttpHeaderUtils {
    public static void injectTraceabilityHeaders(Builder reqBuilder, HttpRequest r, boolean isSsl) {
        String clientIp = r.getClientIp();
        String existingXff = r.getHeader("X-Forwarded-For");
        String xff = (existingXff == null || existingXff.trim().isEmpty()) ? clientIp : (existingXff + ", " + clientIp);
        reqBuilder.header("X-Forwarded-For", xff);

        String proto = isSsl ? "https" : "http";
        if (!isSsl) {
            String existingProto = r.getHeader("X-Forwarded-Proto");
            if (existingProto != null && !existingProto.trim().isEmpty()) {
                proto = existingProto;
            }
        }
        reqBuilder.header("X-Forwarded-Proto", proto);

        String originalHost = r.getHeader("Host");
        if (originalHost != null && !originalHost.trim().isEmpty()) {
            reqBuilder.header("X-Forwarded-Host", originalHost);
        }
    }
}
