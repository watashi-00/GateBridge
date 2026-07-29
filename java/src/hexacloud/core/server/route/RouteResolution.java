package hexacloud.core.server.route;

public class RouteResolution {
    private final String targetClusterName;
    private final String targetSubpath;
    private final boolean matchedRouteRule;
    private final String localRouteName;
    private final String versionPrefix;

    public RouteResolution(String targetClusterName, String targetSubpath, boolean matchedRouteRule, String localRouteName, String versionPrefix) {
        this.targetClusterName = targetClusterName;
        this.targetSubpath = targetSubpath;
        this.matchedRouteRule = matchedRouteRule;
        this.localRouteName = localRouteName;
        this.versionPrefix = versionPrefix != null ? versionPrefix : "";
    }

    public boolean isProxy() { return targetClusterName != null; }
    public boolean isLocal() { return localRouteName != null; }
    public String targetClusterName() { return targetClusterName; }
    public String targetSubpath() { return targetSubpath; }
    public boolean matchedRouteRule() { return matchedRouteRule; }
    public String localRouteName() { return localRouteName; }
    public String versionPrefix() { return versionPrefix; }

    public String resolveTargetRouteKey() {
        if (targetSubpath == null) return null;
        return (versionPrefix + targetSubpath).toUpperCase();
    }
}
