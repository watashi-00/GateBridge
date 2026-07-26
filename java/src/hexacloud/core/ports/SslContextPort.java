package hexacloud.core.ports;

import javax.net.ssl.SSLContext;

/**
 * Port interface for configuring and dynamically reloading SSL/TLS contexts.
 * Enables enterprise modules to implement dynamic TLS termination with zero downtime.
 */
public interface SslContextPort {

    /**
     * Get the active SSLContext for SSL/TLS termination.
     *
     * @return the active SSLContext, or null if not initialized
     */
    SSLContext getSslContext();

    /**
     * Get the configured secure listening port (e.g., 443 or 8443).
     *
     * @return the SSL port
     */
    int getSslPort();

    /**
     * Indicates whether SSL/TLS termination is enabled.
     *
     * @return true if enabled, false otherwise
     */
    boolean isSslEnabled();

    /**
     * Reload keystores and certificates in-memory without dropping active connections.
     */
    void reload();
}
