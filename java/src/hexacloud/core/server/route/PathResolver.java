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

        String routeKey = matchingPath.toUpperCase();
        if (registry.getRoutes().containsKey(routeKey)) {
            return new RouteResolution(null, null, false, routeKey, null);
        }

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
}
