package hexacloud.core.server.route;

import hexacloud.core.utils.common.DebugUtils;

public class RouteRule {
    private final String host;
    private final String pathPattern;
    private final String clusterName;
    private final String targetPath;

    public RouteRule(String host, String pathPattern, String clusterName) {
        this(host, pathPattern, clusterName, null);
    }

    public RouteRule(String host, String pathPattern, String clusterName, String targetPath) {
        this.host = host != null ? host.replaceFirst(":\\d+$", "") : null;
        this.pathPattern = pathPattern;
        this.clusterName = clusterName;
        this.targetPath = normalizeTargetPath(targetPath);
        DebugUtils.info("New rule: " + this);
    }

    public String getHost() { 
        return host; 
    }

    public String getPathPattern() { 
        return pathPattern; 
    }

    public String getClusterName() { 
        return clusterName; 
    }

    public String getTargetPath() {
        return targetPath;
    }

    public boolean matches(String requestHost, String requestPath) {
        String matchedHost = requestHost;
        if (matchedHost != null) {
            matchedHost = matchedHost.replaceFirst(":\\d+$", "");
        }

        if (this.host != null && !this.host.equals("*")) {
            if (matchedHost == null || !matchedHost.equalsIgnoreCase(this.host)) {
                return false;
            }
        }

        if (requestPath == null) {
            return false;
        }

        if (this.pathPattern == null || this.pathPattern.equals("/**") || this.pathPattern.equals("/*")) {
            return true;
        }

        String pattern = this.pathPattern;
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return requestPath.equals(prefix) || requestPath.equals(prefix + "/") || requestPath.startsWith(prefix + "/");
        } 
        

        else if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (requestPath.equals(prefix) || requestPath.equals(prefix + "/")) {
                return true;
            }
            if (requestPath.startsWith(prefix + "/")) {
                String remainder = requestPath.substring(prefix.length() + 1);
                return !remainder.contains("/"); // Garante apenas 1 nível de profundidade
            }
            return false;
        }

        // Exact Match
        return requestPath.equals(pattern);
    }

    public String rewritePath(String requestPath) {
        if (targetPath == null) {
            return "/";
        }
        if (requestPath == null || pathPattern == null) {
            return targetPath;
        }

        String remainder = "";
        if (pathPattern.endsWith("/**")) {
            remainder = extractRemainder(requestPath, pathPattern.substring(0, pathPattern.length() - 3));
        } else if (pathPattern.endsWith("/*")) {
            remainder = extractRemainder(requestPath, pathPattern.substring(0, pathPattern.length() - 2));
        }

        return joinPaths(targetPath, remainder);
    }

    private static String extractRemainder(String requestPath, String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("/")) {
            return requestPath;
        }
        if (requestPath.equals(prefix) || requestPath.equals(prefix + "/")) {
            return "";
        }
        if (requestPath.startsWith(prefix + "/")) {
            return requestPath.substring(prefix.length());
        }
        return "";
    }

    private static String joinPaths(String basePath, String remainder) {
        String base = normalizeTargetPath(basePath);
        if (base == null) {
            base = "/";
        }
        if (remainder == null || remainder.isEmpty() || remainder.equals("/")) {
            return base;
        }
        if (base.equals("/")) {
            return remainder.startsWith("/") ? remainder : "/" + remainder;
        }
        return base + (remainder.startsWith("/") ? remainder : "/" + remainder);
    }

    private static String normalizeTargetPath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty() || trimmed.equals("/")) {
            return "/";
        }
        String normalized = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "RouteRule{" +
                "host='" + host + '\'' +
                ", pathPattern='" + pathPattern + '\'' +
                ", targetClusterName='" + clusterName + '\'' +
                ", targetPath='" + targetPath + '\'' +
                '}';
    }
}
