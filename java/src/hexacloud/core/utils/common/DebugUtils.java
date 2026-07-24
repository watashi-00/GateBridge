package hexacloud.core.utils.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logging utility. Supports filtering by cluster and service host.
 */
public class DebugUtils {

    private static final Logger logger = LoggerFactory.getLogger(DebugUtils.class);

    private static boolean debugEnabled = false;
    private static boolean tuiModeActive = false;
    private static final Queue<LogEntry> recentLogs = new ConcurrentLinkedQueue<>();

    public enum LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    public interface LogListener {
        void onLogAdded();
    }
    private static LogListener logListener;
    public static void setLogListener(LogListener listener) {
        logListener = listener;
    }

    public static class LogEntry {
        private final long timestamp;
        private final LogLevel level;
        private final String clusterName;
        private final String serviceHost;
        private final String message;

        public LogEntry(LogLevel level, String clusterName, String serviceHost, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.clusterName = clusterName != null ? clusterName : "";
            this.serviceHost = serviceHost != null ? serviceHost : "";
            this.message = message;
        }

        public long getTimestamp() { return timestamp; }
        public LogLevel getLevel() { return level; }
        public String getClusterName() { return clusterName; }
        public String getServiceHost() { return serviceHost; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return "[" + level + "] " + message;
        }
    }

    private static final java.io.PrintStream originalOut = System.out;
    private static final java.io.PrintStream originalErr = System.err;

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }


    
    public static void setTuiModeActive(boolean active) {
            if (active) {
                System.setOut(PrintStreamFactory.create(false));
                System.setErr(PrintStreamFactory.create(true));
            } else {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }

    static class RedirectorOutputStream extends java.io.OutputStream {
        private final boolean isError;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        public RedirectorOutputStream(boolean isError) {
            this.isError = isError;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                flushBuffer();
            } else if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        private void flushBuffer() {
            byte[] bytes = buffer.toByteArray();
            buffer.reset();
            if (bytes.length > 0) {
                String line = new String(bytes, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    if (isError) {
                        captureLog(LogLevel.ERROR, null, null, line);
                    } else {
                        captureLog(LogLevel.INFO, null, null, line);
                    }
                }
            }
        }
    }

    public static List<LogEntry> getAllLogs() {
        return new ArrayList<>(recentLogs);
    }

    public static List<LogEntry> getDashboardLogs() {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : recentLogs) {
            if (entry.getLevel() == LogLevel.INFO || entry.getLevel() == LogLevel.ERROR) {
                result.add(entry);
            }
        }
        return result;
    }

    public static List<LogEntry> getClusterLogs(String clusterName) {
        List<LogEntry> result = new ArrayList<>();
        if (clusterName == null || clusterName.isEmpty()) return result;
        for (LogEntry entry : recentLogs) {
            if (clusterName.equalsIgnoreCase(entry.getClusterName())) {
                result.add(entry);
            }
        }
        return result;
    }

    public static List<LogEntry> getServiceLogs(String clusterName, String serviceHost) {
        List<LogEntry> result = new ArrayList<>();
        if (clusterName == null || clusterName.isEmpty() || serviceHost == null || serviceHost.isEmpty()) return result;
        String cleanHost = serviceHost.replaceAll("^[a-zA-Z]+://", "");
        for (LogEntry entry : recentLogs) {
            if (clusterName.equalsIgnoreCase(entry.getClusterName())) {
                String entryHost = entry.getServiceHost() != null ? entry.getServiceHost().replaceAll("^[a-zA-Z]+://", "") : "";
                if (cleanHost.equalsIgnoreCase(entryHost)) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    private static hexacloud.core.ports.LogAdapterPort activeLogAdapter = new hexacloud.core.ports.LogAdapterPort() {
        @Override
        public void log(LogLevel level, String clusterName, String serviceHost, String message, Throwable t) {
            LogEntry entry = new LogEntry(level, clusterName, serviceHost, message);
            recentLogs.offer(entry);
            while (recentLogs.size() > 1000) {
                recentLogs.poll();
            }
            if (!tuiModeActive) {
                if (level == LogLevel.ERROR) {
                    if (t != null) {
                        logger.error(message, t);
                    } else {
                        logger.error(message);
                    }
                } else if (level == LogLevel.WARN) {
                    if (t != null) {
                        logger.warn(message, t);
                    } else {
                        logger.warn(message);
                    }
                } else if (level == LogLevel.DEBUG) {
                    if (t != null) {
                        logger.debug(message, t);
                    } else {
                        logger.debug(message);
                    }
                } else {
                    if (t != null) {
                        logger.info(message, t);
                    } else {
                        logger.info(message);
                    }
                }
            }
            if (logListener != null) {
                logListener.onLogAdded();
            }
        }
    };

    /**
     * Override/set the active log adapter (Inversion of Control).
     * Enables redirection of all internal logs to enterprise handlers.
     */
    public static void setLogAdapter(hexacloud.core.ports.LogAdapterPort adapter) {
        if (adapter != null) {
            activeLogAdapter = adapter;
        }
    }

    /**
     * Gets the active log adapter.
     */
    public static hexacloud.core.ports.LogAdapterPort getLogAdapter() {
        return activeLogAdapter;
    }

    private static void captureLog(LogLevel level, String clusterName, String serviceHost, String message) {
        captureLog(level, clusterName, serviceHost, message, null);
    }

    private static void captureLog(LogLevel level, String clusterName, String serviceHost, String message, Throwable t) {
        activeLogAdapter.log(level, clusterName, serviceHost, message, t);
    }

    public static void log(String message) {
        if (debugEnabled) {
            captureLog(LogLevel.DEBUG, null, null, message);
        }
    }

    public static void info(String message) {
        captureLog(LogLevel.INFO, null, null, message);
    }

    public static void info(String clusterName, String serviceHost, String message) {
        captureLog(LogLevel.INFO, clusterName, serviceHost, message);
    }

    public static void error(String message) {
        captureLog(LogLevel.ERROR, null, null, message);
    }

    public static void error(String clusterName, String serviceHost, String message) {
        captureLog(LogLevel.ERROR, clusterName, serviceHost, message);
    }

    public static void error(String message, Throwable t) {
        error(null, null, message, t);
    }

    public static void error(String clusterName, String serviceHost, String message, Throwable t) {
        if (t != null) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String details = Casts.<String>matchValue(cause)
                .when(java.net.ConnectException.class, e -> " -> Connection refused")
                .when(java.io.IOException.class, e -> (e.getMessage() != null && e.getMessage().contains("timed out")) ? " -> Connection timeout" : null)
                .orElse(" -> " + cause.toString());
            
            if (isNormalNetworkException(t)) {
                captureLog(LogLevel.ERROR, clusterName, serviceHost, message + details, null);
            } else {
                captureLog(LogLevel.ERROR, clusterName, serviceHost, message + details, t);
            }
        } else {
            captureLog(LogLevel.ERROR, clusterName, serviceHost, message, null);
        }
    }

    private static boolean isNormalNetworkException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof java.net.ConnectException ||
                current instanceof java.net.SocketTimeoutException ||
                current instanceof java.net.UnknownHostException ||
                current instanceof java.util.concurrent.TimeoutException ||
                current instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }

            String className = current.getClass().getName();
            if (className.equals("java.net.http.HttpConnectTimeoutException") ||
                className.equals("java.net.http.HttpTimeoutException")) {
                return true;
            }

            if (current instanceof java.io.IOException) {
                String msg = current.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("timed out") || lower.contains("connection refused") || lower.contains("broken pipe") || lower.contains("socket closed")) {
                        return true;
                    }
                }
            }

            current = current.getCause();
        }
        return false;
    }
}
