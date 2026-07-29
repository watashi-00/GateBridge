package hexacloud.core.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import hexacloud.core.utils.common.DebugUtils;

public class EventBusManager {
    private static final EventBusManager GLOBAL = new EventBusManager();

    public static EventBusManager getGlobal() {
        return GLOBAL;
    }

    private final Map<Class<? extends Event>, List<EventListener<?>>> channels = new HashMap<>();
    private final List<EventListener<Event>> interceptors = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addInterceptor(EventListener<Event> interceptor) {
        interceptors.add(interceptor);
    }

    public void removeInterceptor(EventListener<Event> interceptor) {
        interceptors.remove(interceptor);
    }

    public <T extends Event> void sub(Class<T> eventType, EventListener<T> listener) {
        channels.computeIfAbsent(eventType, k -> new ArrayList<>()).add(listener);
    } 

    @SuppressWarnings("unchecked")
    public <T extends Event> void dispatch(T event) {
        Class<? extends Event> eventType = event.getClass();
        List<EventListener<?>> listeners = channels.get(eventType);

        DebugUtils.info("Dispatching event: " + event);

        // Run interceptors
        for (EventListener<Event> interceptor : interceptors) {
            try {
                interceptor.onEvent(event);
            } catch (Exception e) {
                // Ignore interceptor errors
            }
        }

        if(listeners != null) {
            for(EventListener<?> listener : listeners) {
                ((EventListener<T>) listener).onEvent(event);
            }
        }

        if (this != GLOBAL) {
            GLOBAL.dispatch(event);
        }
    }

    public void registerListener(EventController controller) {
        if(controller == null) return;
        
        Class<?> clazz = controller.getClass();
        for(Method method : clazz.getDeclaredMethods()) {
            if(method.isAnnotationPresent(Subscribe.class)) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if(paramTypes.length == 1 && Event.class.isAssignableFrom(paramTypes[0])) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Event> eventType = (Class<? extends Event>) paramTypes[0];
                    method.setAccessible(true);
                    
                    this.sub(eventType, event -> {
                        try {
                            method.invoke(controller, event);
                        } catch(Exception e) {
                            DebugUtils.error("Failed to invoke event handler: " + method.getName(), e);
                        }
                    });
                } else {
                    DebugUtils.error("EventBus: Failed to register method " + clazz.getSimpleName() + "." + method.getName() + " -> Must accept exactly one parameter extending Event");
                }
            }
        }
    }
}
