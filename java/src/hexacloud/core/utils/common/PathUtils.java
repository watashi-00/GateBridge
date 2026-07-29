package hexacloud.core.utils.common;

import java.io.File;

/**
 * Utility class for path resolution and directory traversal helper methods.
 */
public class PathUtils {

    private PathUtils() {}

    /**
     * Finds the resources directory in the workspace tree by walking up to parent directories.
     */
    public static File findResourcesDir() {
        File current = new File(".").getAbsoluteFile();
        for (int i = 0; i < 5; i++) {
            if (current == null) break;
            File resources = new File(current, "resources");
            if (resources.isDirectory()) {
                return resources;
            }
            File srcResources = new File(current, "src/main/resources");
            if (srcResources.isDirectory()) {
                return srcResources;
            }
            current = current.getParentFile();
        }
        return null;
    }

    /**
     * Scans the classpath for classes that implement the specified interface and are not abstract/interface.
     * Restricts the scan to the main application package and framework package for speed and safety.
     */
    public static java.util.List<Class<?>> scanClasspathForImplementations(Class<?> interfaceClass) {
        java.util.List<Class<?>> implementations = new java.util.ArrayList<>();
        String classpath = System.getProperty("java.class.path");
        String pathSeparator = System.getProperty("path.separator");
        String[] entries = classpath.split(pathSeparator);
        String basePackage = getAppBasePackage();

        for (String entry : entries) {
            File file = new File(entry);
            if (!file.exists()) continue;

            if (file.isDirectory()) {
                scanDirectoryForClasses(file, "", interfaceClass, implementations, basePackage);
            } else if (file.isFile() && file.getName().endsWith(".jar")) {
                scanJarForClasses(file, interfaceClass, implementations, basePackage);
            }
        }
        return implementations;
    }

    /**
     * Resolves the main application's base package name.
     * Uses stack trace examination to find the entrypoint class, falling back to system properties.
     */
    public static String getAppBasePackage() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if ("main".equals(element.getMethodName())) {
                String mainClass = element.getClassName();
                int lastDot = mainClass.lastIndexOf('.');
                if (lastDot != -1) {
                    return mainClass.substring(0, lastDot);
                }
            }
        }
        String command = System.getProperty("sun.java.command");
        if (command != null && !command.trim().isEmpty()) {
            String mainClass = command.split(" ")[0];
            if (!mainClass.endsWith(".jar")) {
                int lastDot = mainClass.lastIndexOf('.');
                if (lastDot != -1) {
                    return mainClass.substring(0, lastDot);
                }
            }
        }
        return "";
    }

    private static void scanDirectoryForClasses(File directory, String packageName, Class<?> interfaceClass, java.util.List<Class<?>> implementations, String basePackage) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                if (name.equals(".git") || name.equals("target") || name.equals("build") || 
                    name.equals("bin") || name.equals(".idea") || name.equals(".gradle") || 
                    name.equals(".gemini")) {
                    continue;
                }
                String subPackage = packageName.isEmpty() ? name : packageName + "." + name;
                scanDirectoryForClasses(file, subPackage, interfaceClass, implementations, basePackage);
            } else if (name.endsWith(".class")) {
                String className = packageName.isEmpty() ? 
                    name.substring(0, name.length() - 6) : 
                    packageName + "." + name.substring(0, name.length() - 6);
                tryLoadClass(className, interfaceClass, implementations, basePackage);
            }
        }
    }

    private static void scanJarForClasses(File jarFile, Class<?> interfaceClass, java.util.List<Class<?>> implementations, String basePackage) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = name.replace("/", ".").substring(0, name.length() - 6);
                    tryLoadClass(className, interfaceClass, implementations, basePackage);
                }
            }
        } catch (Exception e) {
            // Ignore jar reading errors
        }
    }

    private static void tryLoadClass(String className, Class<?> interfaceClass, java.util.List<Class<?>> implementations, String basePackage) {
        if (!basePackage.isEmpty() && !className.startsWith(basePackage) && !className.startsWith("hexacloud.")) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(className);
            if (interfaceClass.isAssignableFrom(clazz) && !clazz.isInterface() && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                implementations.add(clazz);
            }
        } catch (Throwable t) {
            // Ignore classes that cannot be loaded
        }
    }
}
