package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunQuotaServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());

    private RunQuotaService service(int maxConcurrent, int hourly, long tokenBudget) {
        return new RunQuotaService(true, maxConcurrent, hourly, tokenBudget, jdbcTemplate, metrics);
    }

    private void stubCounts(int running, int hourlyRuns) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(running, hourlyRuns);
    }

    private void stubTokens(long tokens) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(tokens));
    }

    @Test
    void passesWithinAllBudgets() {
        stubCounts(1, 5);
        stubTokens(1000);
        Assertions.assertDoesNotThrow(() -> service(2, 30, 2_000_000).checkRunAllowed("user-1"));
    }

    @Test
    void rejectsWhenConcurrentRunsExceeded() {
        stubCounts(2, 5);
        stubTokens(0);
        ResponseStatusException exception = Assertions.assertThrows(ResponseStatusException.class,
                () -> service(2, 30, 2_000_000).checkRunAllowed("user-1"));
        Assertions.assertEquals(429, exception.getStatusCode().value());
        Assertions.assertTrue(exception.getReason().contains("并发上限"));
    }

    @Test
    void rejectsWhenHourlyRunsExceeded() {
        stubCounts(1, 30);
        stubTokens(0);
        ResponseStatusException exception = Assertions.assertThrows(ResponseStatusException.class,
                () -> service(2, 30, 2_000_000).checkRunAllowed("user-1"));
        Assertions.assertTrue(exception.getReason().contains("频率上限"));
    }

    @Test
    void rejectsWhenTokenBudgetExhausted() {
        stubCounts(1, 5);
        stubTokens(2_000_000);
        ResponseStatusException exception = Assertions.assertThrows(ResponseStatusException.class,
                () -> service(2, 30, 2_000_000).checkRunAllowed("user-1"));
        Assertions.assertTrue(exception.getReason().contains("预算上限"));
    }

    @Test
    void zeroTokenBudgetDisablesTokenCheck() {
        stubCounts(1, 5);
        Assertions.assertDoesNotThrow(() -> service(2, 30, 0).checkRunAllowed("user-1"));
    }

    @Test
    void disabledServiceNeverRejects() {
        RunQuotaService disabled = new RunQuotaService(false, 2, 30, 2_000_000, jdbcTemplate, metrics);
        Assertions.assertDoesNotThrow(() -> disabled.checkRunAllowed("user-1"));
    }
}
