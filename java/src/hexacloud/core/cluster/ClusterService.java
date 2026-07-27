package hexacloud.core.cluster;

import java.util.HashMap;

import hexacloud.core.cluster.event.ClusterEvent;
import hexacloud.core.event.EventFormat;
import hexacloud.core.model.NodeStatus;
import hexacloud.core.model.NodeUpdateResult;
import hexacloud.core.model.PingProtocol;
import hexacloud.core.utils.common.StrUtils;

/**
 * Service class handling orchestration logic for cluster and node operations.
 */
public class ClusterService {
    private final Cluster cluster;

    public ClusterService(Cluster cluster) {
        this.cluster = cluster;
    }

    /**
     * Process node telemetry update and dispatch lifecycle events.
     * Returns true if successfully updated, false if the target node is not registered.
     */
    public boolean updateTelemetry(TelemetryRequest request) {
        NodeStatus requestedStatus = null;
        if (request.getStatusStr() != null) {
            try {
                requestedStatus = NodeStatus.valueOf(request.getStatusStr().toUpperCase());
            } catch (Exception e) {
                // Ignore invalid status
            }
        }

        NodeUpdateResult result = cluster.updateTelemetryServer(
            request.getHost(),
            request.getPort(),
            request.getCpu(),
            request.getRam(),
            request.getLang(),
            request.getLatency(),
            requestedStatus
        );
        if (result == null) {
            return false;
        }

        NodeStatus finalStatus = requestedStatus != null ? requestedStatus : NodeStatus.ONLINE;
        if (result.statusChanged()) {
            cluster.dispatchEvent(new ClusterEvent.NodeStatusChanged(result.nodeId(), finalStatus, result.nodeId()));
        }
        if (result.telemetryUpdated()) {
            cluster.dispatchEvent(new ClusterEvent.NodeTelemetryUpdated(result.host(), result.nodeId()));
        }
        if (request.getEventName() != null && !StrUtils.isBlank(request.getEventName())) {
            cluster.dispatchEvent(new ClusterEvent.NodeEventSubmitted(
                result.host(),
                request.getPort(),
                normalizeEventProtocol(request.getEventProtocol(), result.protocol()),
                normalizeEventFormat(request.getEventFormat()),
                request.getEventName(),
                new HashMap<>(request.getEventAttributes())
            ));
        }
        return true;
    }

    private PingProtocol normalizeEventProtocol(String requestedProtocol, String fallbackProtocolFriendlyName) {
        String protocolStr = !StrUtils.isBlank(requestedProtocol)
                ? requestedProtocol
                : fallbackProtocolFriendlyName;

        if (StrUtils.isBlank(protocolStr)) {
            return PingProtocol.NONE;
        }
        try {
            return PingProtocol.valueOf(protocolStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            for (PingProtocol proto : PingProtocol.values()) {
                if (proto.getFriendlyName().equalsIgnoreCase(protocolStr.trim())) {
                    return proto;
                }
            }
            return PingProtocol.NONE;
        }
    }

    private EventFormat normalizeEventFormat(String requestedFormat) {
        return EventFormat.fromString(requestedFormat);
    }
}
