package hexacloud.core.ports;

import hexacloud.core.utils.common.DebugUtils.LogLevel;

/**
 * Port interface for capturing and routing framework logs.
 * Allows enterprise integrations to plug in centralized log aggregation systems.
 */
public interface LogAdapterPort {

    /**
     * Route a log entry to the active logging engine.
     *
     * @param level       the log level
     * @param clusterName the associated cluster name, or null
     * @param serviceHost the associated node host, or null
     * @param message     the log message
     * @param t           the associated exception cause, or null
     */
    void log(LogLevel level, String clusterName, String serviceHost, String message, Throwable t);
}
