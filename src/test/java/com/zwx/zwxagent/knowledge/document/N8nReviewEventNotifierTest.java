package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class N8nReviewEventNotifierTest {

    @Test
    void blankWebhookUrlDisablesDispatch() {
        assertThat(new N8nReviewEventNotifier("", new long[]{0, 0, 0}).enabled()).isFalse();
        assertThat(new N8nReviewEventNotifier("   ", new long[]{0, 0, 0}).enabled()).isFalse();
        assertThat(new N8nReviewEventNotifier(null, new long[]{0, 0, 0}).enabled()).isFalse();
        assertThat(new N8nReviewEventNotifier("http://127.0.0.1:5678/webhook/x", new long[]{0, 0, 0}).enabled()).isTrue();
    }

    @Test
    void payloadCarriesCaseIdentifyingFields() throws Exception {
        N8nReviewEventNotifier notifier = new N8nReviewEventNotifier("http://127.0.0.1:5678/webhook/x", new long[]{0, 0, 0});
        N8nReviewEventNotifier.ReviewEventPayload payload = new N8nReviewEventNotifier.ReviewEventPayload(
                "evt-1", N8nReviewEventNotifier.EVENT_TYPE, "doc-1", "default", "super",
                "员工手册v3.pdf", 3, "admin", "INDEXING", "2026-10-25T08:00:00Z");

        String json = new ObjectMapper().writeValueAsString(notifier.payloadMap(payload));

        assertThat(json).contains("\"type\":\"knowledge.review.pending\"");
        assertThat(json).contains("\"documentId\":\"doc-1\"");
        assertThat(json).contains("\"filename\":\"员工手册v3.pdf\"");
        assertThat(json).contains("\"versionNo\":3");
        assertThat(json).contains("\"status\":\"INDEXING\"");
    }
}
