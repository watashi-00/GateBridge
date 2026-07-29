package hexacloud.infra.server;

import hexacloud.core.server.filter.HttpResponse;
import java.io.PrintWriter;

public class DefaultHttpErrorHandler implements HttpErrorHandler {

    @Override
    public void handleException(HttpResponse res, Exception ex) {
        res.setStatus(502);
        res.setContentType("text/plain");
        try (PrintWriter out = res.getWriter()) {
            out.print("502 Bad Gateway - Connection failed: " + ex.getMessage());
        } catch (Exception ignored) {}
    }

    @Override
    public void handleStatus(HttpResponse res, int statusCode, String message) {
        res.setStatus(statusCode);
        res.setContentType("text/plain");
        try (PrintWriter out = res.getWriter()) {
            out.print(statusCode + " " + getStatusText(statusCode) + " - " + message);
        } catch (Exception ignored) {}
    }

    private String getStatusText(int code) {
        switch (code) {
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Internal Server Error";
        }
    }
}
