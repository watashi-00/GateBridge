# Implementation Plan: Apply High-Performance Optimizations

**Goal:** Apply the approved optimizations from `future_optimizations.md` to Undertow and JDK HTTP transports.

- [ ] **Step 1: Tune Undertow socket & buffer settings**
  - In `UndertowHttpTransport.listen()`, set the following `UndertowOptions` on the builder:
    - `ALWAYS_SET_KEEP_ALIVE` = `true`
    - `BUFFER_PIPELINED_DATA` = `true`
    - `RECORD_REQUEST_START_TIME` = `false`
    - `ENABLE_CONNECTOR_STATISTICS` = `false`
  - Explicitly configure thread counts and buffer size:
    - `.setIoThreads(Runtime.getRuntime().availableProcessors())`
    - `.setWorkerThreads(Runtime.getRuntime().availableProcessors())` (restrict native workers since we dispatch to Loom virtual executor)
    - `.setBufferSize(16384)` (16 KB buffer size)

- [ ] **Step 2: Replace HttpURLConnection with Java 21 HttpClient (Connection Pooling)**
  - In both `UndertowHttpTransport.java` and `HttpTransport.java`, instantiate a shared, thread-safe `java.net.http.HttpClient` instance:
    ```java
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
        .version(java.net.http.HttpClient.Version.HTTP_2)
        .connectTimeout(java.time.Duration.ofMillis(5000))
        .build();
    ```
  - In the proxy routing path, replace `HttpURLConnection` with the shared `httpClient`:
    - Build `java.net.http.HttpRequest` with headers, query parameters, method, and request body publisher.
    - Send request using `httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())`.
    - Copy headers and stream response input stream back to the client.

- [ ] **Step 3: Allocation reduction on hot path**
  - In `IpRestrictionFilter` and `TokenAuthFilter`, avoid doing string parsing/matching operations unnecessarily.
  - Lazily parse path and queries where possible.

- [ ] **Step 4: Verify compiles & tests**
  - Verify compilations and run tests.
  - Re-run benchmarks to observe performance impact.
