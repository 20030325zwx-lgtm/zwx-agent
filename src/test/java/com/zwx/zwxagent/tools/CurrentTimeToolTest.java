package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTimeToolTest {

    private final CurrentTimeTool tool = new CurrentTimeTool();

    @Test
    void returnsCurrentTimeForTimeZone() {
        assertEquals(8 * 3600, OffsetDateTime.parse(tool.getCurrentTime("Asia/Shanghai")).getOffset().getTotalSeconds());
    }

    @Test
    void rejectsInvalidTimeZone() {
        assertTrue(tool.getCurrentTime("not/a-zone").startsWith("Invalid time zone:"));
    }
}
