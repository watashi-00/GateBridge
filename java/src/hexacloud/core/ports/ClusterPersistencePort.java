package hexacloud.core.ports;

/**
 * Port interface for persisting and loading GateBridge cluster gateway state configurations.
 */
public interface ClusterPersistencePort {
    
    /**
     * Save the active clusters, nodes, and configurations.
     */
    void saveState();

    /**
     * Load clusters, nodes, and configurations from the storage layer.
     */
    void loadState();

    /**
     * Check if the gateway state has been successfully loaded from storage.
     */
    boolean isStateLoaded();
}
