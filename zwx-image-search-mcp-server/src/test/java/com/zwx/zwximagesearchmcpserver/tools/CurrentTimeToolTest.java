package com.zwx.zwximagesearchmcpserver.tools;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTimeToolTest {
    private final CurrentTimeTool tool = new CurrentTimeTool();

    @Test
    void returnsTimeForRequestedZone() {
        assertTrue(OffsetDateTime.parse(tool.getCurrentTime("Asia/Shanghai")).getOffset().getTotalSeconds() == 8 * 3600);
    }

    @Test
    void reportsInvalidZone() {
        assertTrue(tool.getCurrentTime("not/a-zone").startsWith("Invalid time zone:"));
    }
}
