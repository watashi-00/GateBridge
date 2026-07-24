package hexacloud.core.tui.view;

import java.util.List;
import hexacloud.core.model.ServerNode;
import hexacloud.core.tui.TerminalUI;
import hexacloud.core.tui.TuiRenderer;
import hexacloud.core.tui.TuiState;
import hexacloud.core.utils.common.DebugUtils;
import hexacloud.core.utils.terminal.NativeTerminal;
import hexacloud.core.utils.common.StrUtils;

import static hexacloud.core.tui.TuiConstants.*;

/**
 * Handles visual rendering for the Node Configuration View.
 */
public class NodeConfigViewRenderer {
    private final TerminalUI tui;
    private final TuiRenderer mainRenderer;

    public NodeConfigViewRenderer(TerminalUI tui, TuiRenderer mainRenderer) {
        this.tui = tui;
        this.mainRenderer = mainRenderer;
    }

    public void draw() {
        TuiState state = tui.state();
        if (state.nodes.isEmpty() || state.selectedNodeIndex >= state.nodes.size()) {
            return;
        }

        ServerNode node = state.nodes.get(state.selectedNodeIndex);
        int W = NativeTerminal.getTerminalWidth();
        int H = NativeTerminal.getTerminalHeight();
        if (W < 110) W = 110;
        if (H < 24) H = 24;

        // Top half: configuration parameters
        mainRenderer.drawBox(2, 5, W, 14, "NODE CONFIGURATION PANEL - " + node.getFullHost(), true);

        NativeTerminal.printAt(4, 6, WHITE_BOLD + "Host:   " + RESET + node.host());
        NativeTerminal.printAt(4, 7, WHITE_BOLD + "Port:   " + RESET + node.port());
        
        String coloredStatus = GREEN + "ONLINE" + RESET;
        if (node.status().name().equals("OFFLINE")) {
            coloredStatus = RED + "OFFLINE" + RESET;
        } else if (node.status().name().equals("UNSTABLE")) {
            coloredStatus = YELLOW + "UNSTABLE" + RESET;
        }
        NativeTerminal.printAt(4, 8, WHITE_BOLD + "Status: " + RESET + coloredStatus);

        boolean canEdit = !tui.readOnly() && tui.nodeConfigurationEnabled();

        String protocolColor = GREEN;
        if (node.pingProtocol() == hexacloud.core.model.PingProtocol.NONE) {
            protocolColor = RED;
        } else if (node.pingProtocol() == hexacloud.core.model.PingProtocol.WEBSOCKET) {
            protocolColor = CYAN;
        } else if (node.pingProtocol() == hexacloud.core.model.PingProtocol.TCP) {
            protocolColor = YELLOW;
        } else if (node.pingProtocol() == hexacloud.core.model.PingProtocol.UDP) {
            protocolColor = CYAN;
        } else if (node.pingProtocol() == hexacloud.core.model.PingProtocol.GRPC) {
            protocolColor = WHITE_BOLD;
        }
        NativeTerminal.printAt(4, 9, (canEdit ? WHITE_BOLD + "[P] " : "") + "Ping Proto: " + RESET + protocolColor + node.pingProtocol() + RESET);
        NativeTerminal.printAt(4, 10, (canEdit ? WHITE_BOLD + "[E] " : "") + "Path/Route: " + RESET + CYAN + node.pingPath() + RESET);
        
        String headerName = node.pingHeaderName() == null ? "None" : node.pingHeaderName();
        String headerVal = node.pingHeaderValue() == null ? "None" : node.pingHeaderValue();
        NativeTerminal.printAt(4, 11, (canEdit ? WHITE_BOLD + "[H] " : "") + "Header: " + RESET + CYAN + headerName + RESET);
        NativeTerminal.printAt(4, 12, (canEdit ? WHITE_BOLD + "[V] " : "") + "Token:  " + RESET + CYAN + headerVal + RESET);
        NativeTerminal.printAt(4, 13, "Mutability: " + (node.isDynamic() ? YELLOW + "DYNAMIC" : GREEN + "STATIC") + RESET);

        // Sep & Right half: Live Telemetry
        int xSep = W / 2;
        int xTele = xSep + 2;
        for (int row = 6; row <= 13; row++) {
            NativeTerminal.printAt(xSep, row, "│");
        }
        NativeTerminal.printAt(xTele, 6, WHITE_BOLD + "Live Telemetry Metrics:" + RESET);
        String runtimeDisplay = node.runtime().isEmpty() ? node.pingProtocol().getFriendlyName() : node.runtime();
        NativeTerminal.printAt(xTele, 7, "Runtime/Lang: " + CYAN + runtimeDisplay + RESET);
        
        String latencyStr = node.status().name().equals("ONLINE") ? node.latencyMs() + " ms" : "-";
        NativeTerminal.printAt(xTele, 8, "Latency(RTT): " + GREEN + latencyStr + RESET);
        
        String cpuStr = node.status().name().equals("ONLINE") ? String.format("%.1f %%", node.cpuUsage()) : "-";
        NativeTerminal.printAt(xTele, 9, "CPU Load:     " + YELLOW + cpuStr + RESET);
        
        String ramStr = node.status().name().equals("ONLINE") ? String.format("%.1f MB", node.ramUsage()) : "-";
        NativeTerminal.printAt(xTele, 10, "RAM Memory:   " + CYAN + ramStr + RESET);

        // Bottom half: console logs for this node/service
        mainRenderer.drawBox(2, 15, W, H - 2, "CONSOLE LOGS FOR SERVICE " + node.getFullHost(), false);

        int logsStartY = 16;
        int logsEndY = H - 3;
        int logsLinesCount = logsEndY - logsStartY + 1;
        int y = logsStartY;
        List<DebugUtils.LogEntry> serviceLogs = DebugUtils.getServiceLogs(state.selectedClusterName, node.getFullHost());
        if (serviceLogs.isEmpty()) {
            NativeTerminal.printAt(4, y, "No logs recorded for this service.");
            y++;
        } else {
            int startIdx = Math.max(0, serviceLogs.size() - logsLinesCount);
            int maxLineWidth = W - 7;
            for (int i = startIdx; i < serviceLogs.size(); i++) {
                DebugUtils.LogEntry entry = serviceLogs.get(i);
                String logLine = entry.toString();
                StringBuilder clearedLine = new StringBuilder(logLine);
                while (clearedLine.length() < maxLineWidth) clearedLine.append(" ");
                String outputLine = clearedLine.substring(0, maxLineWidth);

                if (entry.getLevel() == DebugUtils.LogLevel.ERROR) {
                    NativeTerminal.printAt(4, y, RED + outputLine + RESET);
                } else if (entry.getLevel() == DebugUtils.LogLevel.INFO) {
                    NativeTerminal.printAt(4, y, CYAN + outputLine + RESET);
                } else {
                    NativeTerminal.printAt(4, y, outputLine);
                }
                y++;
            }
        }
        for (int r = y; r <= logsEndY; r++) {
            NativeTerminal.printAt(4, r, StrUtils.repeat(" ", W - 7));
        }

        StringBuilder controlsStr = new StringBuilder();
        controlsStr.append(" [Backspace] Console");
        if (canEdit) {
            controlsStr.append("  [P] Toggle Ping  [E] Change Path  [H] Header Name  [V] Value");
        }
        NativeTerminal.printAt(2, H - 1, StrUtils.repeat(" ", W - 4));
        NativeTerminal.printAt(2, H - 1, WHITE_BOLD + "Controls:" + RESET + controlsStr.toString());
    }
}
