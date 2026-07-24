# High-Performance Undertow HTTP Transport Implementation Plan

**Goal:** Integrate the Undertow HTTP engine into GateBridge to achieve 150k+ req/sec throughput.

- [ ] **Step 1: Add Undertow Dependency**
  - Add `undertow-core` version `2.3.13.Final` to `pom.xml`.

- [ ] **Step 2: Create HttpEngine enum**
  - Create `java/src/hexacloud/core/server/HttpEngine.java` containing enum values: `JDK_DEFAULT`, `UNDERTOW`.

- [ ] **Step 3: Update GatewayBuilderPort and LocalGatewayAdapter**
  - Expose `.httpEngine(HttpEngine)` on `GatewayBuilderPort`.
  - Store `HttpEngine` field in `LocalGatewayAdapter` (defaulting to `HttpEngine.JDK_DEFAULT`).
  - Modify `ensureServerManagerInitialized()` in `LocalGatewayAdapter` to configure the chosen `HttpEngine` on `ServerManager`!
    Wait, `ServerManager` needs to know which engine to use. Let's add `HttpEngine` support to `ServerManager`.

- [ ] **Step 4: Update ServerManager**
  - Add `HttpEngine` field to `ServerManager` (with getter/setter, defaulting to `JDK_DEFAULT`).
  - In `ServerManager.listen(port)`, if `httpEnabled` is true, check if `httpEngine` is `HttpEngine.UNDERTOW`.
    If yes, instantiate `UndertowHttpTransport` instead of `HttpTransport`!

- [ ] **Step 5: Implement Undertow Request and Response Wrappers**
  - Create `java/src/hexacloud/infra/server/UndertowHttpRequestImpl.java` implementing `hexacloud.core.server.filter.HttpRequest`.
  - Create `java/src/hexacloud/infra/server/UndertowHttpResponseImpl.java` implementing `hexacloud.core.server.filter.HttpResponse`.

- [ ] **Step 6: Implement UndertowHttpTransport**
  - Create `java/src/hexacloud/infra/server/UndertowHttpTransport.java` implementing `hexacloud.core.server.ServerTransport`.
  - Set up CORS headers.
  - Execute filter chain (`IpRestrictionFilter`, `RateLimitFilter`, `TokenAuthFilter`, custom filters).
  - Handle custom direct routes (like `/hello`).
  - Handle proxy load balancing for `/clusters/<clusterName>/<subpath>` using `HttpURLConnection` (just like `HttpTransport` does).
  - Ensure blocking operations call `exchange.startBlocking()` first.

- [ ] **Step 7: Run verification tests**
  - Verify compile: `mvn clean test`
  - Re-run benchmarks to compare:
    1. Node + Express
    2. Spring Boot
    3. GateBridge (JDK HTTP)
    4. GateBridge (Undertow HTTP)
