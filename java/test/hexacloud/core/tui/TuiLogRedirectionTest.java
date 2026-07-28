package hexacloud.core.tui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TuiLogRedirectionTest {

    @Test
    public void testDefaultRedirectionIsDisabled() {
        TerminalUI ui = new TerminalUI("Test Display Name");
        assertFalse(ui.redirectSystemOut(), "redirectSystemOut should default to false");
    }

    @Test
    public void testRedirectSystemOutConfiguration() {
        TerminalUI ui = new TerminalUI("Test Display Name");
        assertFalse(ui.redirectSystemOut());

        hexacloud.core.ports.TerminalUiPort port = ui.redirectSystemOut(true);
        assertSame(ui, port);
        assertTrue(ui.redirectSystemOut(), "redirectSystemOut should be updated to true when enabled");

        ui.redirectSystemOut(false);
        assertFalse(ui.redirectSystemOut(), "redirectSystemOut should be updated to false when disabled");
    }
}

