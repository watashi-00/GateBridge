package hexacloud.core.cluster.event;

import java.util.Map;
import java.util.Objects;

import hexacloud.core.event.Event;
import hexacloud.core.event.EventFormat;
import hexacloud.core.model.ServerNode;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.PingProtocol;

public interface ClusterEvent extends Event {

    public static class ClusterRegistered implements ClusterEvent {
        private final String clusterName;

        public ClusterRegistered(String clusterName) {
            this.clusterName = clusterName;
        }

        public String clusterName() {
            return clusterName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClusterRegistered that = (ClusterRegistered) o;
            return Objects.equals(clusterName, that.clusterName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clusterName);
        }

        @Override
        public String toString() {
            return "ClusterRegistered[clusterName=" + clusterName + "]";
        }
    }

    public static class NodeRegistered implements ClusterEvent {
        private final ServerNode node;

        public NodeRegistered(ServerNode node) {
            this.node = node;
        }

        public ServerNode node() {
            return node;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeRegistered that = (NodeRegistered) o;
            return Objects.equals(node, that.node);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node);
        }

        @Override
        public String toString() {
            return "NodeRegistered[node=" + node + "]";
        }
    }

    public static class NodeDeregistered implements ClusterEvent {
        private final String host;

        public NodeDeregistered(String host) {
            this.host = host;
        }

        public String host() {
            return host;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeDeregistered that = (NodeDeregistered) o;
            return Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host);
        }

        @Override
        public String toString() {
            return "NodeDeregistered[host=" + host + "]";
        }
    }

    public static class NodeStatusChanged implements ClusterEvent {
        private final String host;
        private final NodeStatus status;
        private final String nodeId;

        public NodeStatusChanged(String host, NodeStatus status, String nodeId) {
            this.host = host;
            this.status = status;
            this.nodeId = nodeId;
        }

        public String host() {
            return host;
        }

        public NodeStatus status() {
            return status;
        }

        public String nodeId() {
            return nodeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeStatusChanged that = (NodeStatusChanged) o;
            return Objects.equals(host, that.host) && status == that.status && Objects.equals(nodeId, that.nodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, status, nodeId);
        }

        @Override
        public String toString() {
            return "NodeStatusChanged[host=" + host + ", status=" + status + ", nodeId=" + nodeId + "]";
        }
    }

    public static class NodeTelemetryUpdated implements ClusterEvent {
        private final String host;
        private final String nodeId;

        public NodeTelemetryUpdated(String host, String nodeId) {
            this.host = host;
            this.nodeId = nodeId;
        }

        public String host() {
            return host;
        }

        public String nodeId() {
            return nodeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeTelemetryUpdated that = (NodeTelemetryUpdated) o;
            return Objects.equals(host, that.host) && Objects.equals(nodeId, that.nodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, nodeId);
        }

        @Override
        public String toString() {
            return "NodeTelemetryUpdated[host=" + host + ", nodeId=" + nodeId + "]";
        }
    }

    public static class NodeEventSubmitted implements ClusterEvent {
        private final String host;
        private final int port;
        private final PingProtocol protocol;
        private final EventFormat format;
        private final String event;
        private final Map<String, String> attributes;

        public NodeEventSubmitted(String host, int port, PingProtocol protocol, EventFormat format, String event,
                                  Map<String, String> attributes) {
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.format = format;
            this.event = event;
            this.attributes = attributes;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public PingProtocol protocol() {
            return protocol;
        }

        public EventFormat format() {
            return format;
        }

        public String event() {
            return event;
        }

        public Map<String, String> attributes() {
            return attributes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeEventSubmitted that = (NodeEventSubmitted) o;
            return port == that.port &&
                    Objects.equals(host, that.host) &&
                    protocol == that.protocol &&
                    format == that.format &&
                    Objects.equals(event, that.event) &&
                    Objects.equals(attributes, that.attributes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, protocol, format, event, attributes);
        }

        @Override
        public String toString() {
            return "NodeEventSubmitted[host=" + host + ", port=" + port + ", protocol=" + protocol +
                    ", format=" + format + ", event=" + event + ", attributes=" + attributes + "]";
        }
    }
}
