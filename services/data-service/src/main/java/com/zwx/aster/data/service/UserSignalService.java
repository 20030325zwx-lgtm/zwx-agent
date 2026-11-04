package com.zwx.aster.data.service;

import com.zwx.aster.data.repository.UserSignalRepository;
import com.zwx.aster.data.security.JwtVerifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 隐式信号：一次埋齐「采纳 / 编辑 / 重生成 / 复制 / 导出」。
 * 它们比点赞密集得多，是主观质量最便宜的真值，也用于交叉验证 judge 是否失准。
 */
@Service
public class UserSignalService {

    private static final Set<String> EVENTS = Set.of("adopt", "edit", "regenerate", "copy", "export");
    private static final Set<String> SUBJECT_TYPES = Set.of("run", "asset", "conversation", "project");

    private final UserSignalRepository signalRepository;

    public UserSignalService(UserSignalRepository signalRepository) {
        this.signalRepository = signalRepository;
    }

    public void record(JwtVerifier.Actor actor, String event, String subjectType, String subjectId,
                       Map<String, Object> props) {
        String normalizedEvent = event == null ? "" : event.trim().toLowerCase();
        if (!EVENTS.contains(normalizedEvent)) {
            throw new IllegalArgumentException("event 只支持 " + EVENTS);
        }
        if (subjectType == null || !SUBJECT_TYPES.contains(subjectType)) {
            throw new IllegalArgumentException("subjectType 只支持 " + SUBJECT_TYPES);
        }
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId 不能为空");
        }
        signalRepository.insert(actor.tenantId(), actor.userId(), normalizedEvent, subjectType,
                subjectId.trim(), props);
    }

    public Map<String, Object> summary(JwtVerifier.Actor actor, int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        return signalRepository.summary(actor.tenantId(), Instant.now().minus(safeDays, ChronoUnit.DAYS));
    }

    public List<Map<String, Object>> recent(JwtVerifier.Actor actor, int limit) {
        return signalRepository.recent(actor.tenantId(), limit);
    }
}
