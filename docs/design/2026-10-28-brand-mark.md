# 品牌标记与应用图标

日期：2026-10-28
状态：已落地（Web + 桌面打包共用同一套标记）

## 背景

原有应用图标 / 空状态头像是一个带表情的卡通机器人（渐变蓝底 + 黄色星星 + 眨眼动画）。产品进入商业化阶段后，这套形象偏「玩具感」，与 Codex 这类工具的克制、几何、单色标识不在同一个语境。本次替换为纯几何标记，同时保留「星枢」语义。

## 标记定义

- 形态：四角星，尖角 + 深凹边，单色填充，无描边、无渐变、无动画。
- 画布：`viewBox="0 0 32 32"`，路径：

```svg
<path d="M16 2C16.6 11.6 20.4 15.4 30 16 20.4 16.6 16.6 20.4 16 30 15.4 20.4 11.6 16.6 2 16 11.6 15.4 15.4 11.6 16 2Z"/>
```

- 选型过程：对比了四角星（饱满 / 纤细 / 折中）、八向星、四叶风车、竖长横短导航星。八向星在小尺寸糊成一团，风车偏花卉，最终选「折中」曲率——16px 仍可辨认，96px 有清晰锐角。
- 组件：`frontend/src/components/AsterMark.vue`（`currentColor`，尺寸由父级 CSS 决定）。

## 配色与令牌

| 令牌 | 浅色 | 深色 |
| --- | --- | --- |
| `--wb-mark-bg` | `#10131B` | `#10131B` |
| `--wb-mark-fg` | `#FFFFFF` | `#FFFFFF` |
| `--wb-mark-ring` | 透明 | `rgba(255,255,255,0.16)` |

深色模式下保持同一块墨色底，用 1px 内描边把标记从深色背景里「托」出来；不做主题反转，避免出现刺眼的白色方块。

## 落地位置

- 应用图标：`desktop/assets/icon.svg`（1024，824 圆角方块居中，标记视觉宽度约 450），经 `desktop/scripts/generate-icon.sh` 生成 `icon.icns`（该文件被 `desktop/.gitignore` 忽略，打包时重新生成）。
- 浏览器：`frontend/public/favicon.svg`（64，10 内边距）、`frontend/public/app-icon.svg` → `apple-touch-icon.png`（180 满底，交给 iOS 自己切圆角）；`index.html` 已改为引用 SVG 图标并补 `theme-color`。
- 产品内：`AsterAvatar.vue` 改为「墨色圆角方块 + 白色标记」，尺寸 56%；`GlobalNav` 品牌位、`Login.vue` 徽标、`AiAvatarFallback` 统一使用同一令牌。
- 空状态头像保持 72px 方块，阴影由蓝色光晕改为中性 `--wb-shadow-lg`。

## 去卡通化（同日追加）

- 右下角悬浮的卡通助手已移除（带表情、眨眼、漂浮的机器人 SVG），改为克制的状态条：26px 墨色图标块（标记 / 加载环 / 对勾 / 警示）+ 状态文字 + 说明，空闲时完全隐藏。
- 对话头像兜底里的 `🤖` / `❤️` emoji 移除：默认统一用产品标记，`love` 类型改用线性心形图标。
- 任务页的 `📋` / `⏸` 横幅 emoji 换成 `lucide` 图标，与整体线性图标体系一致。
- `Home.vue` 里装饰性的 `✦` 文本漂浮元素保留（该页已由 `/workbench` 取代，不再对外）。

## 验证

- `npm run build` 通过。
- 浅色 / 深色截图核对：导航品牌位、空状态头像、图标对比度正常；16px favicon 可辨认。
- `icon.icns` 重新生成（1024），打包配置无需改动。

## 后续注意

- 修改标记几何时，需要同步 `AsterMark.vue`、`favicon.svg`、`app-icon.svg`、`desktop/assets/icon.svg` 四处路径。
- 桌面端发布前执行 `npm run prepare:icon`（或 `dist:mac` 会自带）。
