# 项目内置 Skill

项目内置 Skill 是 Spring 服务中的受控能力，不是 Codex 桌面端的开发 Skill。一个 Skill 必须同时具备：元数据、授权判断、提示词触发规则和实际 `ToolCallback`；仅把能力写进提示词并不能让模型执行它。

## 当前 Skill

| ID | 关联工具 | 适用智能体 | 授权条件 |
| --- | --- | --- | --- |
| `web-research` | `WebSearchTool.searchWeb` | `love`、`travel`、`test` | 用户在本轮开启“联网搜索” |

## 页面配置

打开首页右上角的“Skill 配置”，或任一智能体设置菜单中的“内置 Skill 配置”。配置按租户和智能体持久化；Web 与桌面端使用同一个 Vue 构建产物和同一组 API，保存后下一轮对话立即生效。

## 触发流程

```text
用户消息 + 联网开关
  -> BuiltInSkillRegistry: 筛选本轮获授权 Skill
  -> SkillPromptBuilder: 将触发条件写入 system prompt
  -> toolCallbacks: 仅暴露已授权 Skill 的实际工具给模型
  -> 模型判断需要时发起 function call
  -> 工具结果回填模型，生成最终回答
```

`SkillPromptBuilder` 的规则要求模型仅在用户请求满足触发条件时调用 Skill；对于实时信息优先使用 `web-research`，工具无结果或失败时明确说明无法确认。关闭联网开关时，提示词会禁止外部能力，同时不会注入任何工具回调。

## 新增 Skill

1. 在 `BuiltInSkillRegistry` 中声明 ID、说明、触发条件和可用智能体。
2. 为 Skill 配置最小权限的 `ToolCallback`，不要复用全量工具数组。
3. 在 `availableFor` 中实现每轮授权判断，并让 `toolCallbacksFor` 与它保持一致。
4. 为触发、未授权、工具失败三个分支添加单元或集成测试。

高副作用操作，例如文件写入、终端命令、下载和 PDF 生成，在具备用户确认、审计和沙箱前不得默认作为对话 Skill 暴露。
