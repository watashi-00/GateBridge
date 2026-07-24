package hexacloud.core.utils.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import hexacloud.core.ports.LogAdapterPort;
import hexacloud.core.utils.common.DebugUtils.LogLevel;

/**
 * Unit tests for custom logging redirection via LogAdapterPort interface.
 */
public class DebugUtilsTest {

    @Test
    public void testCustomLogAdapterInjection() {
        final boolean[] logCalled = {false};
        final LogLevel[] capturedLevel = {null};
        final String[] capturedMessage = {null};

        LogAdapterPort customAdapter = new LogAdapterPort() {
            @Override
            public void log(LogLevel level, String clusterName, String serviceHost, String message, Throwable t) {
                logCalled[0] = true;
                capturedLevel[0] = level;
                capturedMessage[0] = message;
            }
        };

        LogAdapterPort originalAdapter = DebugUtils.getLogAdapter();
        try {
            DebugUtils.setLogAdapter(customAdapter);

            DebugUtils.info("my-cluster", "my-host", "This is an enterprise log message");

            assertTrue(logCalled[0], "Custom log adapter should have been called");
            assertEquals(LogLevel.INFO, capturedLevel[0], "Logged level should match");
            assertEquals("This is an enterprise log message", capturedMessage[0], "Logged message should match");
        } finally {
            DebugUtils.setLogAdapter(originalAdapter);
        }
    }
}
