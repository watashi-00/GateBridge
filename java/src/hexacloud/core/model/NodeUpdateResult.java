package hexacloud.core.model;

public record NodeUpdateResult(String host, String protocol, boolean statusChanged, boolean telemetryUpdated, String nodeId) {}
