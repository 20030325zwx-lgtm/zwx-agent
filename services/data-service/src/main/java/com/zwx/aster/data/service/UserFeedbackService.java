package com.zwx.aster.data.service;

import com.zwx.aster.data.domain.UserFeedbackRecord;
import com.zwx.aster.data.repository.UserFeedbackRepository;
import com.zwx.aster.data.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 数据中台 D2：用户反馈采集 → BadCase 根因归类 → 一键回流评测集（调 eval-service 内部接口）。
 * 反馈创建时冻结来源快照（只读查询主库 agent_run），后续编辑不影响回流用例。
 */
@Service
public class UserFeedbackService {

    private static final String INTERNAL_HEADER = "X-Aster-Internal-Token";

    /** BadCase 根因分类：回答「这个月最多的问题该改哪一层」。 */
    public static final Set<String> ROOT_CAUSES = Set.of(
            "knowledge_gap", "retrieval_miss", "prompt_weak", "tool_args",
            "intent_misroute", "refusal_fail", "latency", "other");

    private final UserFeedbackRepository feedbackRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient evalClient;
    private final String internalToken;

    public UserFeedbackService(UserFeedbackRepository feedbackRepository,
                               JdbcTemplate jdbcTemplate,
                               @Value("${app.internal.eval-base-url:http://eval-service:8140}") String evalBaseUrl,
                               @Value("${app.internal.token:}") String internalToken) {
        this.feedbackRepository = feedbackRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.evalClient = RestClient.builder().baseUrl(evalBaseUrl).build();
        this.internalToken = internalToken;
    }

    /** 一次点击入库：chat 来源按 runId 只读快照 question/answer；标签可在同一次请求里带上。 */
    public UserFeedbackRecord create(JwtVerifier.Actor actor, String rating, String reason,
                                     List<String> tags, String runId, String conversationId) {
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("rating 必须是 up 或 down");
        }
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("source", "chat");
        if (runId != null && !runId.isBlank()) {
            Optional<Map<String, Object>> run = findRunSnapshot(actor, runId, conversationId);
            if (run.isEmpty()) {
                throw new IllegalArgumentException("run 不存在或无权访问");
            }
            refs.put("runId", runId);
            if (conversationId != null) refs.put("conversationId", conversationId);
            refs.put("question", run.get().get("input"));
            refs.put("answer", run.get().get("answer"));
        }
        List<String> safeTags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
        UserFeedbackRecord record = new UserFeedbackRecord(UUID.randomUUID(), actor.tenantId(), actor.userId(),
                rating, "chat", "open", reason, safeTags, refs, null, null, null, null, null, null, null);
        return feedbackRepository.insert(record);
    }

    /** 只读快照：主库 agent_run（E2 生成日志的最小字段）。 */
    private Optional<Map<String, Object>> findRunSnapshot(JwtVerifier.Actor actor, String runId, String conversationId) {
        String sql = """
                SELECT input, COALESCE(final_answer, partial_answer, '') AS answer
                FROM agent_run
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                """ + (conversationId == null || conversationId.isBlank() ? "" : " AND conversation_id = ?");
        List<Map<String, Object>> rows = conversationId == null || conversationId.isBlank()
                ? jdbcTemplate.queryForList(sql, runId, actor.tenantId(), actor.userId())
                : jdbcTemplate.queryForList(sql, runId, actor.tenantId(), actor.userId(), conversationId);
        return rows.stream().findFirst();
    }

    public List<UserFeedbackRecord> list(JwtVerifier.Actor actor, String status, String rating, String source,
                                         String rootCause, Long cursorMillis, String cursorId, int limit) {
        return feedbackRepository.list(actor.tenantId(), status, rating, source, rootCause,
                cursorMillis, cursorId, Math.min(Math.max(limit, 1), 100));
    }

    public Map<String, Long> counts(JwtVerifier.Actor actor) {
        return feedbackRepository.counts(actor.tenantId());
    }

    /** 治理看板：总量、根因分布、回流转化率。 */
    public Map<String, Object> stats(JwtVerifier.Actor actor) {
        List<Map<String, Object>> byRootCause = feedbackRepository.rootCauseCounts(actor.tenantId());
        long total = feedbackRepository.total(actor.tenantId());
        long converted = byRootCause.stream()
                .mapToLong(row -> ((Number) row.get("converted")).longValue()).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("converted", converted);
        result.put("convertedRate", total == 0 ? null : Math.round(1000.0 * converted / total) / 1000.0);
        result.put("byRootCause", byRootCause);
        return result;
    }

    public UserFeedbackRecord get(JwtVerifier.Actor actor, UUID id) {
        UserFeedbackRecord record = feedbackRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("反馈不存在：" + id));
        if (!record.tenantId().equals(actor.tenantId())) {
            throw new IllegalArgumentException("反馈不存在：" + id);
        }
        return record;
    }

    public void updateStatus(JwtVerifier.Actor actor, UUID id, String targetStatus) {
        if (!"acked".equals(targetStatus) && !"ignored".equals(targetStatus)) {
            throw new IllegalArgumentException("status 只支持 acked / ignored（converted 由回流产生）");
        }
        get(actor, id);
        feedbackRepository.transitionGuarded(id, targetStatus);
    }

    /** 根因归类：根因/负责人/结论可反复更新，不影响状态机。 */
    public UserFeedbackRecord updateAnalysis(JwtVerifier.Actor actor, UUID id, String rootCause,
                                             String assignee, String resolution) {
        get(actor, id);
        String normalized = null;
        if (rootCause != null && !rootCause.isBlank()) {
            normalized = rootCause.trim().toLowerCase();
            if (!ROOT_CAUSES.contains(normalized)) {
                throw new IllegalArgumentException("rootCause 只支持 " + ROOT_CAUSES);
            }
        }
        feedbackRepository.updateAnalysis(id, actor.tenantId(), normalized,
                assignee == null || assignee.isBlank() ? null : assignee.trim(),
                resolution == null || resolution.isBlank() ? null : resolution.trim());
        return get(actor, id);
    }

    /** 一键回流：调 eval-service 内部接口，成功后本地标记 converted 并保存链接。 */
    public Map<String, Object> convert(JwtVerifier.Actor actor, UUID id) {
        UserFeedbackRecord record = get(actor, id);
        if (record.converted()) {
            throw new IllegalArgumentException("该反馈已回流过（case " + record.convertedCaseId() + "）");
        }
        Map<String, Object> refs = record.refs();
        String question = stringOf(refs.get("question"));
        String answer = stringOf(refs.get("answer"));
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("该反馈没有来源问题快照，无法回流（仅 chat + run 来源支持）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", actor.tenantId());
        body.put("feedbackId", record.id().toString());
        body.put("rating", record.rating());
        body.put("reason", backflowReason(record));
        body.put("question", question);
        body.put("answer", answer);
        Map<String, Object> response;
        try {
            response = evalClient.post().uri("/api/internal/eval/backflow")
                    .header(INTERNAL_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("回流失败（评测中台不可用）：" + exception.getMessage(), exception);
        }
        if (response == null || response.get("datasetId") == null) {
            throw new IllegalStateException("回流失败：评测中台未返回数据集");
        }
        UUID datasetId = UUID.fromString(String.valueOf(response.get("datasetId")));
        UUID caseId = response.get("caseId") == null ? null : UUID.fromString(String.valueOf(response.get("caseId")));
        if (!feedbackRepository.markConverted(record.id(), datasetId, caseId)) {
            throw new IllegalArgumentException("反馈状态已变化，回流未生效（可能已被并发处理）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId.toString());
        result.put("caseExternalId", response.get("caseExternalId"));
        return result;
    }

    /** 回流理由带上根因与结论，让用例在评测集里自带上下文。 */
    private static String backflowReason(UserFeedbackRecord record) {
        StringBuilder reason = new StringBuilder();
        if (record.classified()) {
            reason.append("[根因:").append(record.rootCause()).append("] ");
        }
        if (record.resolution() != null && !record.resolution().isBlank()) {
            reason.append(record.resolution()).append(' ');
        }
        if (record.reason() != null && !record.reason().isBlank()) {
            reason.append(record.reason());
        }
        return reason.toString().trim();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
