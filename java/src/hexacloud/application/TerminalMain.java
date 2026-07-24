package hexacloud.application;

import hexacloud.core.model.NodeStatus;
import hexacloud.core.ports.GatewayBuilderPort;
import hexacloud.core.ports.RunningGatewayPort;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.tui.TerminalUiFactory;
import hexacloud.infra.gateway.GatewayFactory;

public class TerminalMain {
    public static void main(String[] args) {
        DebugUtils.setDebugEnabled(false);
        
        GatewayBuilderPort builder = GatewayFactory.createGateway("main-gw")
            .createCluster("watashi-00")
            .port(3000)
            .pingInterval(5)
            .enableTelnet(true)
            .enableHttp(true)
            .enableWs(true)
            .registerFilter(new Main.CustomAppLoggingFilter());

        builder.registerServer(3001, NodeStatus.OFFLINE)
            .registerServer(3002, NodeStatus.OFFLINE)
            .registerServer(3003, NodeStatus.OFFLINE)
            .registerServer(3004, NodeStatus.OFFLINE);
            
        // Register port 3005 with custom health-check path and header using the new NodeBuilder API
        builder.registerNode("http://localhost", 3005)
            .pingEnabled(true)
            .pingPath("/")
            .pingHeader("Authorization", "Bearer showCaseToken")
            .register();

        RunningGatewayPort runningGateway = builder.listen()
            .startPingScheduler();

        // Launch the DevOps Panel in non-blocking toggle mode (detach/reattach with ENTER)
        TerminalUiFactory.createTui("MyCompany - GateBridge DevOps Panel")
            .seedGateway(runningGateway)
            .startToggleMode();
    }
}
