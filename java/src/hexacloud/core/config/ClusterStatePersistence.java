package hexacloud.core.config;

import hexacloud.core.ports.ClusterPersistencePort;

/**
 * Persistence delegation layer to serialize and deserialize cluster and node gateway configurations.
 * Allows runtime injection of custom adapters (e.g. Redis) using {@link ClusterPersistencePort}.
 */
public class ClusterStatePersistence {

    private static ClusterPersistencePort activeAdapter = new LocalFilePersistenceAdapter();

    private ClusterStatePersistence() {}

    /**
     * Sets the active persistence adapter (Inversion of Control).
     * Allows Enterprise modules to override file-based storage with distributed databases.
     */
    public static void setPersistenceAdapter(ClusterPersistencePort adapter) {
        if (adapter != null) {
            activeAdapter = adapter;
        }
    }

    /**
     * Gets the active persistence adapter.
     */
    public static ClusterPersistencePort getPersistenceAdapter() {
        return activeAdapter;
    }

    /**
     * Check if the gateway state has been successfully loaded from the active persistence adapter.
     */
    public static boolean isStateLoaded() {
        return activeAdapter.isStateLoaded();
    }

    /**
     * Save the current active clusters, nodes, and configurations using the active persistence adapter.
     */
    public static synchronized void saveState() {
        activeAdapter.saveState();
    }

    /**
     * Load clusters, registered nodes, and configurations using the active persistence adapter.
     */
    public static synchronized void loadState() {
        activeAdapter.loadState();
    }
}
