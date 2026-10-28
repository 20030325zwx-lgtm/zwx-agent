# 桌面本机目录授权：系统选择器与受控读取

日期：2026-10-29。状态：已实现并验证（提交 f21430a）。
承接：[桌面 C/D/E 交付](./2026-10-29-desktop-phase-c-d-delivery.md) 中"本机文件存储适配"的授权部分。

## 授权模型

```
用户（系统目录选择器）→ Electron 主进程（持久化授权清单）
    → 回环令牌 POST /ai/desktop/grants → 后端授权清单（内存态）
    → 工作区 select/browse 以清单为边界 → 文件读取天然受限
```

- **授权入口唯一**：只有 macOS 原生目录选择器（`dialog.showOpenDialog`）能产生授权；
  渲染进程与后端都无法自行扩大范围。
- **授权清单持久化在主进程**（`desktop-config.json` 的 grantedDirectories），应用重启保留；
  后端每次就绪由主进程重推（后端内存态，避免两处真相不一致）。
- **最小暴露**：`/ai/desktop` 仅在桌面运行激活（`app.desktop.loopback-token` 已配置），
  网页部署请求返回 404；未授权路径绑定/浏览返回 400 与明确中文提示。
- **桌面模式语义**：授权目录可为本机任意位置（绕过网页部署的 ROOT 相对路径限制），
  项目 `path_scope=LOCAL`；下游工作区文件读取以项目路径为源，天然被授权边界约束。

## 改动清单

| 层 | 内容 |
| --- | --- |
| Electron main | `desktop:choose-directory`（原生选择器+持久化+推送）、grants 读写 IPC、就绪后推送 |
| preload | `chooseDirectory / directoryGrants / revokeDirectoryGrant` |
| 后端 | `DesktopDirectoryGrantService`（归一化+前缀匹配）、`DesktopController`、`ProjectWorkspaceService` select/browse 校验 |
| 前端 | 桌面模式侧栏工作区对话框显示「选择本机目录…」，授权后直接绑定 |

## 验证

- `verify-desktop-chain.sh` 第 6 步：同步授权 200 → 授权目录内子目录绑定 200 →
  未授权目录绑定 400（"目录未授权：请在桌面端通过系统目录选择器重新授权该目录"）。
- 打包应用实测：主进程推送返回 200，后端日志出现"桌面目录授权已同步：N 个目录"。
- 单测：`ProjectWorkspaceServiceTest` 补充未激活授权服务注入（原语义不变）。

## 后续方向

1. 授权管理界面：查看/撤销已授权目录（后端 DELETE 已就绪，前端入口待加）。
2. 授权粒度扩展：只读授权 vs 读写授权（写操作如产物落盘）。
3. 工作区文件面板展示授权目录内容（复用现有 `listWorkspaceFiles`）。
4. 网页部署的对应能力仍是服务端目录（现有行为），不引入此授权模型。
