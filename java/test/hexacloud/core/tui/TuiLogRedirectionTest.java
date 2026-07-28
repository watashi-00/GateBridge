package hexacloud.core.tui;

import org.junit.jupiter.api.Test;
import java.io.PrintStream;
import static org.junit.jupiter.api.Assertions.*;

public class TuiLogRedirectionTest {
    @Test
    public void testDefaultRedirectionIsDisabled() {
        PrintStream originalOut = System.out;
        TerminalUI ui = new TerminalUI("Test Display Name");
        // By default, system output should not be hijacked before run, and optional
        assertEquals(originalOut, System.out);
    }

    @Test
    public void testRedirectSystemOutConfiguration() {
        TerminalUI ui = new TerminalUI("Test Display Name");
        hexacloud.core.ports.TerminalUiPort port = ui.redirectSystemOut(true);
        assertSame(ui, port);
    }
}
