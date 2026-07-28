package hexacloud.application;

public class OnlyTerminalMain {
    public static void main(String[] args) {
        hexacloud.core.tui.TerminalUiFactory.createTui("MyCompany - GateBridge DevOps Panel")
            .redirectSystemOut(true)
            .start();
    }
}
