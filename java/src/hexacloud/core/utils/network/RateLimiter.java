package hexacloud.core.utils.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

public class RateLimiter {

    private volatile int maxRequests;
    private volatile long windowSizeMs;
    private final ReentrantLock limitLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ClientWindow> clientRequestWindows = new ConcurrentHashMap<>();

    private static class ClientWindow {
        final Queue<Long> timestamps = new ConcurrentLinkedQueue<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    public RateLimiter(int maxRequests, int durationSeconds) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = durationSeconds * 1000L;
    }

    public void updateLimits(int maxRequests, int durationSeconds) {
        limitLock.lock();
        try {
            this.maxRequests = maxRequests;
            this.windowSizeMs = durationSeconds * 1000L;
        } finally {
            limitLock.unlock();
        }
    }

    public boolean allowRequest(String clientId) {
        int maxReq = maxRequests;
        long windowSize = windowSizeMs;

        if(maxReq <= 0 || windowSize <= 0) {
            return true;
        }
        
        long now = System.currentTimeMillis();
        ClientWindow window = clientRequestWindows.computeIfAbsent(clientId, k -> new ClientWindow());

        window.lock.lock();
        try {
            while(!window.timestamps.isEmpty() && (now - window.timestamps.peek() > windowSize)) {
                window.timestamps.poll();
            }

            if(window.timestamps.size() < maxReq) {
                window.timestamps.offer(now);
                return true;
            }
            return false;
        } finally {
            window.lock.unlock();
        }
    }
}
