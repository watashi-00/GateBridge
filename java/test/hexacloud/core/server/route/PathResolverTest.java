package hexacloud.core.server.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PathResolverTest {
    @Test
    public void testCleanSlashesAndResolveLocal() {
        RouteRegistry registry = new RouteRegistry();
        registry.registerController(new RouteController() {
            @RouteMapping("/v1/list_clusters")
            public void test(String args, java.io.PrintWriter out) {}
        });

        RouteResolution res = PathResolver.resolve("//v1//list_clusters", "localhost", registry);
        assertTrue(res.isLocal());
        assertEquals("/V1/LIST_CLUSTERS", res.localRouteName());
        assertFalse(res.isProxy());
    }

    @Test
    public void testResolveProxyWithVersionPrefix() {
        RouteRegistry registry = new RouteRegistry();
        RouteResolution res = PathResolver.resolve("/v1/clusters/watata/get_nodes", "localhost", registry);
        assertTrue(res.isProxy());
        assertEquals("watata", res.targetClusterName());
        assertEquals("/get_nodes", res.targetSubpath());
        assertEquals("/V1/GET_NODES", res.resolveTargetRouteKey());
    }
}
