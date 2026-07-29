package hexacloud.core.server.route;

import java.util.List;

public class PathResolver {

    public static RouteResolution resolve(String path, String host, RouteRegistry registry) {
        if (path == null) {
            return new RouteResolution(null, null, false, null, null);
        }

        String matchingPath = path.trim();
        while (matchingPath.contains("//")) {
            matchingPath = matchingPath.replace("//", "/");
        }

        // 1. Resolve local route key using slash and prefix tolerance
        String localRouteKey = findLocalRouteKey(matchingPath, registry);
        if (localRouteKey != null) {
            return new RouteResolution(null, null, false, localRouteKey, null);
        }

        // 2. Resolve proxy paths (/clusters/{name}/...)
        int clustersIdx = matchingPath.indexOf("/clusters/");
        if (clustersIdx != -1) {
            String prefix = matchingPath.substring(0, clustersIdx);
            String pathWithoutClusters = matchingPath.substring(clustersIdx + 10);
            int slashIdx = pathWithoutClusters.indexOf('/');
            String targetClusterName;
            String clusterSubpath;
            if (slashIdx != -1) {
                targetClusterName = pathWithoutClusters.substring(0, slashIdx);
                clusterSubpath = pathWithoutClusters.substring(slashIdx);
            } else {
                targetClusterName = pathWithoutClusters;
                clusterSubpath = "/";
            }
            return new RouteResolution(targetClusterName, clusterSubpath, false, null, prefix);
        }

        // 3. Match Ingress rules
        List<RouteRule> rules = registry.getRouteRulesList();
        if (rules != null && !rules.isEmpty()) {
            for (RouteRule rule : rules) {
                if (rule.matches(host, matchingPath)) {
                    return new RouteResolution(rule.getClusterName(), rule.rewritePath(matchingPath), true, null, null);
                }
            }
        }

        return new RouteResolution(null, null, false, null, null);
    }

    private static String findLocalRouteKey(String matchingPath, RouteRegistry registry) {
        String routeKey = matchingPath.toUpperCase();
        
        // Try exact match
        if (registry.getRoutes().containsKey(routeKey)) {
            return routeKey;
        }
        
        // Try stripping leading slash
        if (routeKey.startsWith("/") && routeKey.length() > 1) {
            String stripped = routeKey.substring(1);
            if (registry.getRoutes().containsKey(stripped)) {
                return stripped;
            }
        }
        
        // Try adding leading slash
        if (!routeKey.startsWith("/")) {
            String withSlash = "/" + routeKey;
            if (registry.getRoutes().containsKey(withSlash)) {
                return withSlash;
            }
        }

        // Try prefix/unprefix matching with "/V1"
        if (routeKey.startsWith("/V1/") || routeKey.equals("/V1")) {
            String unv1 = routeKey.equals("/V1") ? "/" : routeKey.substring(3);
            if (registry.getRoutes().containsKey(unv1)) {
                return unv1;
            }
            if (unv1.startsWith("/") && unv1.length() > 1) {
                String stripped = unv1.substring(1);
                if (registry.getRoutes().containsKey(stripped)) {
                    return stripped;
                }
            }
        } else {
            String withV1 = "/V1" + (routeKey.startsWith("/") ? routeKey : "/" + routeKey);
            if (registry.getRoutes().containsKey(withV1)) {
                return withV1;
            }
            if (withV1.startsWith("/") && withV1.length() > 1) {
                String stripped = withV1.substring(1);
                if (registry.getRoutes().containsKey(stripped)) {
                    return stripped;
                }
            }
        }

        return null;
    }
}
