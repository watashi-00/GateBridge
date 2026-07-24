package hexacloud.core.server.route;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import hexacloud.core.utils.common.DebugUtils;

public class RouteRegistry {

    private final Map<String, BiConsumer<String, PrintWriter>> routes = new HashMap<>();
    private final java.util.Set<String> publicRoutes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.List<RouteRule> routeRules = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addRouteRule(RouteRule rule) {
        if (rule != null) {
            this.routeRules.add(rule);
        }
    }

    public java.util.List<RouteRule> getRouteRulesList() {
        return routeRules;
    }

    public java.util.List<RouteRule> getRouteRules() {
        return routeRules;
    }

    public boolean isRoutePublic(String routeName) {
        if (routeName == null) return false;
        return publicRoutes.contains(routeName.toUpperCase());
    }

    public void registerController(RouteController controller) {
        if(controller == null) return;
        
        Class<?> clazz = controller.getClass();
        for(Method method : clazz.getDeclaredMethods()) {
            if(method.isAnnotationPresent(RouteMapping.class)) {
                RouteMapping mapping = method.getAnnotation(RouteMapping.class);
                String command = mapping.value().toUpperCase();
                if (mapping.isPublic()) {
                    publicRoutes.add(command);
                }
                
                Class<?>[] paramTypes = method.getParameterTypes();
                if(paramTypes.length == 2 && paramTypes[0] == String.class && paramTypes[1] == PrintWriter.class) {
                    method.setAccessible(true);
                    BiConsumer<String, PrintWriter> handler;
                    try {
                        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
                        java.lang.invoke.MethodHandle mh = lookup.unreflect(method);
                        final java.lang.invoke.MethodHandle boundMh = mh.bindTo(controller);
                        handler = (args, out) -> {
                            try {
                                boundMh.invoke(args, out);
                            } catch(Throwable e) {
                                DebugUtils.error("Failed to invoke route method: " + method.getName(), e);
                                out.println("ERROR: Internal server error");
                            }
                        };
                    } catch (Exception ex) {
                        handler = (args, out) -> {
                            try {
                                method.invoke(controller, args, out);
                            } catch(Exception e) {
                                DebugUtils.error("Failed to invoke route method: " + method.getName(), e);
                                out.println("ERROR: Internal server error");
                            }
                        };
                    }
                    routes.put(command, handler);
                    DebugUtils.log("RouteScanner: Registered command '" + command + "' mapping to method " + clazz.getSimpleName() + "." + method.getName());
                } else {
                    DebugUtils.error("RouteScanner: Failed to register method " + clazz.getSimpleName() + "." + method.getName() + " -> Must accept parameters (String, PrintWriter)");
                }
            }
        }
    }

    public Map<String, BiConsumer<String, PrintWriter>> getRoutes() {
        return routes;
    }
}
