package hexacloud.core.server.filter;

import java.io.PrintWriter;

public interface HttpResponse {
    void setHeader(String name, String value);
    void setStatus(int statusCode);
    void setContentType(String contentType);
    PrintWriter getWriter() throws Exception;
    boolean isCommitted();
}
