package hexacloud.core.config;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import hexacloud.core.utils.common.DebugUtils;

public class EnvLoader {

    private static final Properties properties = new Properties();
    private static final Map<String, String> configCache = new ConcurrentHashMap<>();

    static {
        boolean loaded = false;
        String propFileName = "hexacloud.properties";
        String customPath = System.getProperty("hexacloud.config.file");

        if (customPath == null)
            customPath = System.getenv("HEXACLOUD_CONFIG_FILE");
        // 1. Try loading from custom path
        if(customPath != null)
            loaded = loadEnvPath(customPath);
        
        // 2. Try loading from classpath
        if(!loaded)
            loaded = loadEnvPath(propFileName, true);

        // 3. Try loading from dynamically resolved resources/ directory
        if(!loaded) {
            java.io.File resourcesDir = hexacloud.core.utils.common.PathUtils.findResourcesDir();
            if (resourcesDir != null) {
                java.io.File propFile = new java.io.File(resourcesDir, propFileName);
                if (propFile.exists()) {
                    loaded = loadEnvPath(propFile.getPath());
                }
            }
        }

        // 4. Try loading from CWD
        if(!loaded) {
            java.io.File propFile = new java.io.File(propFileName);
            if (propFile.exists()) {
                loaded = loadEnvPath(propFile.getPath());
            }
        }

        if (!loaded) {
            DebugUtils.error("EnvLoader: No '"+propFileName+"' found. Using system environment variables and defaults.");
        }
    }

    private static boolean loadEnvPath(String path) {
        return loadEnvPath(path, false);
    }

    private static boolean loadEnvPath(String path, Boolean stream) {
        if(path == null || path.trim().isEmpty()) return false;

        try(InputStream input = stream ? EnvLoader.class.getClassLoader().getResourceAsStream(path) : new FileInputStream(path)) {
            if(input == null) throw new IOException("Resource not found: " + path);
            properties.load(input);
            DebugUtils.info("EnvLoader: Loaded configurations from file '" + path + "'");
            return true;
        } catch( IOException ex ) {
            DebugUtils.info("Envloader: Config file not found at path: " + path);
            return false;
        }
    }

    public static String get(String clusterName, String propertyName, String defaultValue) {
        
        String cacheKey = clusterName + ":" + propertyName;
        
        return configCache.computeIfAbsent(cacheKey, k ->
            resolveProperty(clusterName, propertyName, defaultValue)
        );
    }

    private static String resolveProperty(String clusterName, String propertyName, String defaultValue) {
        
        String envKey = ("cluster_" + clusterName + "_" + propertyName).toUpperCase().replace("-", "_");
        String value = System.getenv(envKey);
        if(value != null) return value.trim();

        String key = "cluster." + clusterName + "." + propertyName;
        value = properties.getProperty(key);
        if(value != null) return value.trim();

        String defaultEnvKey = ("cluster_default_" + propertyName).toUpperCase();
        value = System.getenv(defaultEnvKey);
        if(value != null) return value.trim();

        String defaultKey = "cluster.default." + propertyName;
        value = properties.getProperty(defaultKey);
        if(value != null) return value.trim();

        return defaultValue;
    }

    public static boolean getBoolean(String clusterName, String propertyName, boolean defaultValue) {
        String value = get(clusterName, propertyName, String.valueOf(defaultValue));
        if(value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }

    public static int getInt(String clusterName, String propertyName, int defaultValue) {
        String value = get(clusterName, propertyName, String.valueOf(defaultValue));
        if(value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch(NumberFormatException e) {
            return defaultValue;
        }
    }
}
