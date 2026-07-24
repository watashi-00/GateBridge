# Gateway Benchmark Infrastructure Plan

**Goal:** Create three benchmark projects (Spring Boot, Node.js + Express, GateBridge) and a Node.js-based benchmarking script using `autocannon` to compare latency and throughput.

**Directory Structure:**
All benchmark files will reside in `/home/watashi/Projects/Java-framework/gatebridge-benchmarks/`.
- `/home/watashi/Projects/Java-framework/gatebridge-benchmarks/node-express/` (Node + Express project)
- `/home/watashi/Projects/Java-framework/gatebridge-benchmarks/springboot/` (Spring Boot project)
- `/home/watashi/Projects/Java-framework/gatebridge-benchmarks/gatebridge-app/` (GateBridge framework application)
- `/home/watashi/Projects/Java-framework/gatebridge-benchmarks/run-benchmarks.js` (Main runner script)

---

### Step-by-Step Implementation Steps

- [ ] **Step 1: Create Node.js + Express Benchmark Project**
  - Create `node-express/package.json` with dependencies `express`.
  - Create `node-express/server.js` running Express on port `8081` with a `/hello` endpoint returning plain text `"hello"`.

- [ ] **Step 2: Create Spring Boot Benchmark Project**
  - Create `springboot/pom.xml` configured with Java 21, Spring Boot Starter Web parent/dependencies, and packaging configurations.
  - Create `springboot/src/main/java/benchmark/SpringBootApplication.java` running on port `8082` with a REST controller `/hello` returning plain text `"hello"`.

- [ ] **Step 3: Create GateBridge Benchmark Project**
  - Create `gatebridge-app/pom.xml` using dependency `io.hexacloud:gatebridge-core:1.3.0-release` (compiled/installed locally).
  - Create `gatebridge-app/src/main/java/benchmark/GateBridgeApplication.java` starting the gateway on base port `8079` (HTTP on `8080`) with a custom route controller returning plain text `"hello"` for `/hello` endpoint.

- [ ] **Step 4: Create Benchmark Runner Script**
  - In `gatebridge-benchmarks/`, create a `package.json` declaring `autocannon`.
  - Run `npm install` inside `gatebridge-benchmarks/` to download `autocannon` locally.
  - Create `gatebridge-benchmarks/run-benchmarks.js` that:
    1. Spawns `node-express/server.js` on port `8081`.
    2. Runs autocannon load test (e.g. 100 concurrent connections for 10 seconds).
    3. Stops the process cleanly.
    4. Spawns `springboot` jar on port `8082`.
    5. Runs autocannon load test.
    6. Stops the process cleanly.
    7. Spawns `gatebridge-app` on port `8080` (HTTP on `8080`).
    8. Runs autocannon load test.
    9. Stops the process cleanly.
    10. Prints a markdown table comparing Latency (Average, P50, P90, P99), Throughput (Requests/sec), and Total Requests/Errors.

- [ ] **Step 5: Run the benchmarks and write results report**
  - Build `springboot` and `gatebridge-app` using `mvn clean package`.
  - Execute `node run-benchmarks.js` to run the comparison.
  - Write results to `/home/watashi/Projects/Java-framework/gatebridge-benchmarks/results.md`.
