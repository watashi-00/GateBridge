package hexacloud.infra.server;

import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UndertowHttpResponseImplTest {

    @Test
    public void testGetWriterReturnsBufferedPrintWriter() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(exchange.isResponseStarted()).thenReturn(false);
        when(exchange.getOutputStream()).thenReturn(baos);

        UndertowHttpResponseImpl response = new UndertowHttpResponseImpl(exchange);

        PrintWriter writer = response.getWriter();
        assertNotNull(writer);
        assertSame(writer, response.getWriter());

        verify(exchange).setStatusCode(200);

        writer.println("Test Undertow Buffering");
        // Before flushBuffer, because auto-flush is false, output stream has not received data
        assertEquals(0, baos.size(), "Buffer should not auto-flush on println");

        response.flushBuffer();
        assertEquals("Test Undertow Buffering\n", baos.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}
