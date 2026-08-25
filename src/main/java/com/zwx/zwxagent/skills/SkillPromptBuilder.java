package com.zwx.zwxagent.skills;

import org.springframework.stereotype.Component;

import java.util.List;

/** Builds the system-prompt section that tells a model when a granted skill may be used. */
@Component
public class SkillPromptBuilder {
    private final BuiltInSkillRegistry skillRegistry;

    public SkillPromptBuilder(BuiltInSkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public String build(String agentKey, boolean webSearchEnabled) {
        return build("default", agentKey, webSearchEnabled);
    }

    public String build(String tenantId, String agentKey, boolean webSearchEnabled) {
        List<BuiltInSkill> skills = skillRegistry.availableFor(tenantId, agentKey, webSearchEnabled);
        if (skills.isEmpty()) {
            return "\n\n【内置 Skill】本轮未授权任何外部 Skill。不得声称已联网查询、访问网页或调用外部工具。";
        }

        String definitions = skills.stream()
                .map(skill -> "- " + skill.id() + "（" + skill.name() + "）：" + skill.description() + " 触发条件：" + skill.trigger())
                .reduce("", (left, right) -> left + "\n" + right);
        return """

                【内置 Skill】本轮仅可使用下列已授权 Skill：%s
                调用规则：
                1. 只有用户请求满足某个 Skill 的触发条件时，才调用该 Skill 关联的工具；普通常识问题不要调用。
                2. 对时效性事实优先调用 web-research；工具失败、没有结果或证据不足时，明确说明无法确认，不能补造结果。
                3. 只能根据工具实际返回的内容陈述查询结论；回答中说明已使用的 Skill，不能声称调用了未授权 Skill。
                4. 用户未开启联网查询时，外部工具绝对不可用。
                """.formatted(definitions);
    }
}
