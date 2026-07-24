package hexacloud.core.model;

/**
 * Represents a registered service node inside a cluster.
 * Contains connection coordinates, status metadata, and health-check configurations.
 */
public class ServerNode {
    private final String name;
    private final String host;
    private final int port;
    private final NodeStatus status;
    private final boolean isExternal;
    private final PingProtocol pingProtocol;
    private final String pingPath;
    private final String pingHeaderName;
    private final String pingHeaderValue;
    private final boolean isDynamic;
    private final boolean telemetryOnly;

    private int latencyMs = 0;
    private double cpuUsage = 0.0;
    private double ramUsage = 0.0;
    private String runtime = "";

    /**
     * Primary constructor including node name, isDynamic, and telemetryOnly flags.
     */
    public ServerNode(String name, String host, int port, NodeStatus status, boolean isExternal,
                      PingProtocol pingProtocol, String pingPath, String pingHeaderName, String pingHeaderValue, boolean isDynamic, boolean telemetryOnly) {
        this.name = name != null && !name.isEmpty() ? name : (host + ":" + port);
        this.host = host;
        this.port = port;
        this.status = status;
        this.isExternal = isExternal;
        this.pingProtocol = pingProtocol != null ? pingProtocol : PingProtocol.HTTP;
        this.pingPath = pingPath != null ? pingPath : "/";
        this.pingHeaderName = pingHeaderName;
        this.pingHeaderValue = pingHeaderValue;
        this.isDynamic = isDynamic;
        this.telemetryOnly = telemetryOnly;
    }

    /**
     * Constructor including node name and isDynamic flag.
     */
    public ServerNode(String name, String host, int port, NodeStatus status, boolean isExternal,
                      PingProtocol pingProtocol, String pingPath, String pingHeaderName, String pingHeaderValue, boolean isDynamic) {
        this(name, host, port, status, isExternal, pingProtocol, pingPath, pingHeaderName, pingHeaderValue, isDynamic, false);
    }

    /**
     * Legacy constructor including node name.
     */
    public ServerNode(String name, String host, int port, NodeStatus status, boolean isExternal,
                      PingProtocol pingProtocol, String pingPath, String pingHeaderName, String pingHeaderValue) {
        this(name, host, port, status, isExternal, pingProtocol, pingPath, pingHeaderName, pingHeaderValue, false);
    }

    /**
     * Legacy constructor without node name parameter.
     */
    public ServerNode(String host, int port, NodeStatus status, boolean isExternal,
                      PingProtocol pingProtocol, String pingPath, String pingHeaderName, String pingHeaderValue) {
        this(host + ":" + port, host, port, status, isExternal, pingProtocol, pingPath, pingHeaderName, pingHeaderValue);
    }

    public ServerNode(String name, String host, int port, NodeStatus status, boolean isExternal,
                      boolean pingEnabled, String pingPath, String pingHeaderName, String pingHeaderValue) {
        this(name, host, port, status, isExternal, pingEnabled ? PingProtocol.HTTP : PingProtocol.NONE, pingPath, pingHeaderName, pingHeaderValue);
    }

    /**
     * Compatibility constructor using boolean pingEnabled.
     */
    public ServerNode(String host, int port, NodeStatus status, boolean isExternal,
                      boolean pingEnabled, String pingPath, String pingHeaderName, String pingHeaderValue) {
        this(host + ":" + port, host, port, status, isExternal, pingEnabled ? PingProtocol.HTTP : PingProtocol.NONE, pingPath, pingHeaderName, pingHeaderValue);
    }

    public ServerNode(String name, String host, int port, NodeStatus status, boolean isExternal) {
        this(name, host, port, status, isExternal, PingProtocol.HTTP, "/", null, null);
    }

    /**
     * Constructor for default health-check settings.
     */
    public ServerNode(String host, int port, NodeStatus status, boolean isExternal) {
        this(host + ":" + port, host, port, status, isExternal, PingProtocol.HTTP, "/", null, null);
    }

    /**
     * Get the node name identifier.
     */
    public String name() {
        return name;
    }

    /**
     * Get the protocol and host (e.g. "http://localhost").
     */
    public String host() {
        return host;
    }

    /**
     * Get the service node port number.
     */
    public int port() {
        return port;
    }

    /**
     * Get the current status of the service node.
     */
    public NodeStatus status() {
        return status;
    }

    /**
     * Returns true if the node is located outside the local intranet/gateway.
     */
    public boolean isExternal() {
        return isExternal;
    }

    /**
     * Returns the selected health-check ping protocol.
     */
    public PingProtocol pingProtocol() {
        return pingProtocol != null ? pingProtocol : PingProtocol.HTTP;
    }

    /**
     * Returns true if health-check ping requests are enabled for this node.
     */
    public boolean pingEnabled() {
        return pingProtocol != PingProtocol.NONE;
    }

    /**
     * Get the health-check path endpoint (e.g. "/healthz").
     */
    public String pingPath() {
        return pingPath;
    }

    /**
     * Get the custom health-check API token header key.
     */
    public String pingHeaderName() {
        return pingHeaderName;
    }

    /**
     * Get the custom health-check API token header value.
     */
    public String pingHeaderValue() {
        return pingHeaderValue;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs = latencyMs;
    }

    public double cpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public double ramUsage() {
        return ramUsage;
    }

    public void setRamUsage(double ramUsage) {
        this.ramUsage = ramUsage;
    }

    public String runtime() {
        return runtime != null ? runtime : "";
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public boolean isDynamic() {
        return isDynamic;
    }

    public boolean telemetryOnly() {
        return telemetryOnly;
    }

    public ServerNode withDynamic(boolean isDynamic) {
        ServerNode node = new ServerNode(this.name, this.host, this.port, this.status, this.isExternal,
                this.pingProtocol, this.pingPath, this.pingHeaderName, this.pingHeaderValue, isDynamic, this.telemetryOnly);
        node.setLatencyMs(this.latencyMs);
        node.setCpuUsage(this.cpuUsage);
        node.setRamUsage(this.ramUsage);
        node.setRuntime(this.runtime);
        return node;
    }

    /**
     * Create a new immutable ServerNode instance with an updated status.
     */
    public ServerNode withStatus(NodeStatus newStatus) {
        ServerNode node = new ServerNode(this.name, this.host, this.port, newStatus, this.isExternal,
                this.pingProtocol, this.pingPath, this.pingHeaderName, this.pingHeaderValue, this.isDynamic, this.telemetryOnly);
        node.setLatencyMs(this.latencyMs);
        node.setCpuUsage(this.cpuUsage);
        node.setRamUsage(this.ramUsage);
        node.setRuntime(this.runtime);
        return node;
    }

    /**
     * Create a new immutable ServerNode instance with an updated ping protocol.
     */
    public ServerNode withPingProtocol(PingProtocol newProtocol) {
        ServerNode node = new ServerNode(this.name, this.host, this.port, this.status, this.isExternal,
                newProtocol, this.pingPath, this.pingHeaderName, this.pingHeaderValue, this.isDynamic, this.telemetryOnly);
        node.setLatencyMs(this.latencyMs);
        node.setCpuUsage(this.cpuUsage);
        node.setRamUsage(this.ramUsage);
        node.setRuntime(this.runtime);
        return node;
    }

    /**
     * Return host and port formatted together (e.g. "http://localhost:8080").
     */
    public String getFullHost() {
        return this.host + ":" + this.port;
    }

    /**
     * Returns the host string without its protocol/scheme prefix (e.g. "localhost" instead of "http://localhost").
     */
    public String getHostWithoutProtocol() {
        if (host == null) return "";
        return host.replaceAll("^[a-zA-Z]+://", "");
    }

    @Override
    public String toString() {
        return "ServerNode{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", status=" + status +
                ", isExternal=" + isExternal +
                ", pingProtocol=" + pingProtocol +
                ", pingPath='" + pingPath + '\'' +
                '}';
    }
}
