package hexacloud.core.cluster.event;

import java.util.Map;

import hexacloud.core.event.Event;
import hexacloud.core.event.EventFormat;
import hexacloud.core.model.ServerNode;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.PingProtocol;

/**
 * Marker interface for all Cluster Domain Events.
 * Groups all concrete event payloads as nested records to simplify package structure.
 */
public interface ClusterEvent extends Event {

    record ClusterRegistered(String clusterName) implements ClusterEvent {}

    record NodeRegistered(ServerNode node) implements ClusterEvent {}

    record NodeDeregistered(String host) implements ClusterEvent {}

    record NodeStatusChanged(String host, NodeStatus status, String nodeId) implements ClusterEvent {}

    record NodeTelemetryUpdated(String host, String nodeId) implements ClusterEvent {}

    record NodeEventSubmitted(String host, int port, PingProtocol protocol, EventFormat format, String event,
                              Map<String, String> attributes) implements ClusterEvent {}
}
