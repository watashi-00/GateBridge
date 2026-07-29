package hexacloud.core.model;

/**
 * Defines how the gateway routes traffic TO this ServerNode.
 * Independent of PingProtocol (which controls health-check behavior).
 *
 * HTTP  — node accepts HTTP/HTTPS reverse-proxy traffic (default).
 * TCP   — node accepts raw TCP tunneled traffic only.
 * GRPC  — node accepts gRPC traffic (HTTP/2 with content-type enforcement).
 */
public enum RoutingProtocol {
    HTTP,
    TCP,
    GRPC
}
