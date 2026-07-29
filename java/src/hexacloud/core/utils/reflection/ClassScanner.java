package hexacloud.core.utils.reflection;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.lang.reflect.Modifier;

/**
 * Helper class that uses ClassLoader to search packages for implementations of a given interface.
 * Works in both standard filesystem paths (IDE) and JAR files (Docker).
 */
public class ClassScanner {

    private ClassScanner() {}

    /**
     * Scans the given package and its subpackages for implementations of the specified interface/class.
     * Excludes interfaces and abstract classes.
     *
     * @param packageName     the root package to scan (e.g., "hexacloud.core.server.route")
     * @param targetInterface the target interface or class to find implementations for
     * @param <T>             the interface/class type
     * @return a list of concrete classes implementing the target interface
     */
    @SuppressWarnings("unchecked")
    public static <T> List<Class<? extends T>> scanPackage(String packageName, Class<T> targetInterface) {
        List<Class<? extends T>> classes = new ArrayList<>();
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ClassScanner.class.getClassLoader();
        }

        try {
            Enumeration<URL> resources = classLoader.getResources(packagePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();

                if ("file".equals(protocol)) {
                    try {
                        File directory = new File(resource.toURI());
                        scanDirectory(directory, packageName, targetInterface, classes);
                    } catch (Exception e) {
                        // Fallback to URL decoding if toURI fails
                        String filePath = URLDecoder.decode(resource.getFile(), "UTF-8");
                        scanDirectory(new File(filePath), packageName, targetInterface, classes);
                    }
                } else if ("jar".equals(protocol)) {
                    scanJar(resource, packagePath, targetInterface, classes);
                }
            }
        } catch (IOException e) {
            // Ignore package scanning errors
        }

        return classes;
    }

    private static <T> void scanDirectory(File directory, String packageName, Class<T> targetInterface, List<Class<? extends T>> classes) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), targetInterface, classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                tryLoadClass(className, targetInterface, classes);
            }
        }
    }

    private static <T> void scanJar(URL resource, String packagePath, Class<T> targetInterface, List<Class<? extends T>> classes) {
        String packagePathWithSlash = packagePath.endsWith("/") ? packagePath : packagePath + "/";
        try {
            String jarPath = resource.getPath();
            if (jarPath.startsWith("file:")) {
                int bangIndex = jarPath.indexOf('!');
                if (bangIndex != -1) {
                    String fileUrlStr = jarPath.substring(0, bangIndex);
                    try {
                        File file = new File(new java.net.URI(fileUrlStr));
                        try (JarFile jar = new JarFile(file)) {
                            scanJarEntries(jar, packagePathWithSlash, targetInterface, classes);
                            return; // Scanned successfully using local JarFile
                        }
                    } catch (Exception e) {
                        // Fallback
                    }
                }
            }

            // Fallback via JarURLConnection
            JarURLConnection jarConnection = (JarURLConnection) resource.openConnection();
            JarFile jarFile = jarConnection.getJarFile();
            scanJarEntries(jarFile, packagePathWithSlash, targetInterface, classes);
        } catch (IOException e) {
            // Ignore jar reading errors
        }
    }

    private static <T> void scanJarEntries(JarFile jarFile, String packagePathWithSlash, Class<T> targetInterface, List<Class<? extends T>> classes) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(packagePathWithSlash) && name.endsWith(".class")) {
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                tryLoadClass(className, targetInterface, classes);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void tryLoadClass(String className, Class<T> targetInterface, List<Class<? extends T>> classes) {
        try {
            Class<?> clazz = Class.forName(className);
            if (targetInterface.isAssignableFrom(clazz) 
                    && !clazz.isInterface() 
                    && !Modifier.isAbstract(clazz.getModifiers())) {
                classes.add((Class<? extends T>) clazz);
            }
        } catch (Throwable t) {
            // Ignore classes that fail to load
        }
    }
}
