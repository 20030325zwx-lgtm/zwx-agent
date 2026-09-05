package com.zwx.zwximagesearchmcpserver.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class CurrentTimeTool {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Tool(description = "Get the current date and time for an IANA time zone, such as Asia/Shanghai or UTC")
    public String getCurrentTime(@ToolParam(description = "IANA time zone identifier") String timeZone) {
        try {
            ZoneId zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.trim());
            return OffsetDateTime.now(zone).format(FORMATTER);
        } catch (Exception exception) {
            return "Invalid time zone: " + timeZone;
        }
    }
}
