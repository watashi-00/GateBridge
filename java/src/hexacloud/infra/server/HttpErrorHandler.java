package hexacloud.infra.server;

import hexacloud.core.server.filter.HttpResponse;

public interface HttpErrorHandler {
    void handleException(HttpResponse res, Exception ex);
    void handleStatus(HttpResponse res, int statusCode, String message);
}
