package hexacloud.core.utils.reflection;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import hexacloud.core.server.route.RouteController;
import hexacloud.core.server.route.ClusterController;

public class ClassScannerTest {

    @Test
    public void testScanPackage() {
        List<Class<? extends RouteController>> implementations = 
            ClassScanner.scanPackage("hexacloud.core.server.route", RouteController.class);

        assertNotNull(implementations);
        
        // Assert that we found ClusterController
        boolean foundClusterController = false;
        for (Class<? extends RouteController> clazz : implementations) {
            if (clazz.equals(ClusterController.class)) {
                foundClusterController = true;
            }
            
            // Assert that no interfaces or abstract classes are returned
            assertFalse(clazz.isInterface(), "Should not return interface: " + clazz.getName());
            assertFalse(java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()), 
                "Should not return abstract class: " + clazz.getName());
        }

        assertTrue(foundClusterController, "Should have found ClusterController in package hexacloud.core.server.route");
    }
}
