package hexacloud.core.server.filter;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class FilterChainTest {
    @Test
    public void testChainExecutionSequence() throws Exception {
        List<HttpFilter> filters = new ArrayList<>();
        List<String> executionLog = new ArrayList<>();

        filters.add((req, res, chain) -> {
            executionLog.add("filter-1");
            chain.doFilter(req, res);
        });
        filters.add((req, res, chain) -> {
            executionLog.add("filter-2");
            chain.doFilter(req, res);
        });

        HttpFilterChainImpl chain = new HttpFilterChainImpl(filters, (req, res) -> {
            executionLog.add("target-route");
        });

        chain.doFilter(null, null);

        assertEquals(List.of("filter-1", "filter-2", "target-route"), executionLog);
    }
}
