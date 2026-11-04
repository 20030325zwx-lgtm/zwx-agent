package com.zwx.aster.data.web;

import com.zwx.aster.data.security.JwtVerifier;
import com.zwx.aster.data.service.UserSignalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 隐式信号上报与聚合：前端埋点写，管理员看。 */
@RestController
@RequestMapping("/api/ai/feedback/signals")
public class UserSignalController {

    private final UserSignalService signalService;

    public UserSignalController(UserSignalService signalService) {
        this.signalService = signalService;
    }

    public record SignalRequest(String event, String subjectType, String subjectId, Map<String, Object> props) {
    }

    /** 任何登录用户都可上报（埋点不得因为权限失败而丢数据）。 */
    @PostMapping
    public Map<String, Object> record(JwtVerifier.Actor actor, @RequestBody SignalRequest request) {
        signalService.record(actor, request.event(), request.subjectType(), request.subjectId(), request.props());
        return Map.of("recorded", true);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(JwtVerifier.Actor actor, @RequestParam(defaultValue = "7") int days) {
        actor.requireAdmin();
        return signalService.summary(actor, days);
    }

    @GetMapping
    public List<Map<String, Object>> recent(JwtVerifier.Actor actor,
                                            @RequestParam(defaultValue = "50") int limit) {
        actor.requireAdmin();
        return signalService.recent(actor, limit);
    }
}
