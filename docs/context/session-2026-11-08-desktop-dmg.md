# 会话摘要：桌面端 DMG 打包 + 体积分析（2026-11-08 晚）

## 需求

权限硬化批次提交推送（06914f1 / 0.7.32）后，打包新 DMG 同步桌面端；要求先解释
「安装包 1 点几个 G」是否合理。

## 体积实测（0.1.2 基线）

- **DMG 下载 337MB；安装后 .app 633MB**（`desktop/release/mac-arm64/Aster.app`）。
- 成分：Electron Framework 248MB（固定成本）+ pgserver 147MB（其中 libicudata.dylib 61MB）
  + 后端 Spring Boot fat jar 122MB（~990 个依赖，无异常胖块）+ 前端 app.asar 60MB
  + jlink JVM 54MB。
- 「1 点几个 G」的感知来源：release 目录里 dmg+zip 各 337MB（合计 674MB）；以及系统存储
  视角 = 安装后 .app 633MB + 运行数据（内嵌 Postgres pgdata、日志、Electron 缓存）合计过 1GB。
- 已核查无意外膨胀：无 node_modules 泄漏、JVM 已 jlink、pgserver 已做过 dylib 去重、单架构。
- CJK 字体（16MB）在 vendor 与主包各一份（两套引擎路径各自校验 sha256），无安全去重路径，不动。

## 改动（desktop/package.json）

- 版本 0.1.2 → **0.1.3**（JAR 为 `mvn clean package` 全新构建，含本日全部 agent 改造）。
- mac target 去掉 zip（发布产物减半，dmg 是唯一分发物）。
- 尝试 ULMO(lzma)：electron-builder 26 schema 不允许（enum 到 ULFO）；改用
  `compression: "maximum"` → DMG 自动 UDBZ(bzip2)。
- 实测结论：**UDBZ 342MB ≈ UDZO 337MB**——内容已是压缩形态，DMG 体积由结构决定，
  打包参数已无油水。

## 验证

- `npm run dist:mac` 全链通过；产物 `desktop/release/Aster-0.1.3-arm64.dmg`（342MB，UDBZ）。
- jar 内确认含 PendingConfirmationSweeper.class（权限硬化在内）。
- 冒烟：launch 新 .app → 内嵌 Postgres 127.0.0.1:54329 + JVM 后端启动 → `/api/health` = ok
  （管理端口 401 属预期）→ DMG 挂载验证（.app 657M + Applications 链接）→ 干净退出。
- 首跑在 `~/Library/Application Support/aster-desktop` 生成了合法 pgdata，直接复用无害。

## 后续可挖（未实施，按收益排序）

1. Tauri 化替代 Electron：-240MB（改动最大，收益最大）。
2. 无 ICU 构建的 Postgres：-77MB（需源码编译换掉 Zonky 二进制）。
3. 后端 jar 依赖瘦身（swagger-ui+knife4j 7.6MB、opennlp、mysql 驱动等）：-15~30MB。
4. file-viewer 按 `formats` 裁剪渲染器（iwork/hangul/ofd 等）：-5~10MB，需产品决策。
5. CJK 字体 woff2 化：约 -8MB，需改 file-viewer 资源链。

## 注意

- electron-builder 的 DMG 压缩字段名是 `dmg.format`（schema enum: UDBZ/UDCO/UDRO/UDRW/
  UDZO/ULFO），`writeFormat` 不存在；ULMO 被 schema 拒绝。
- 冒烟时若本机 8123 被开发后端占用无影响：桌面端未配置 backendPort 时自行分配空闲端口。
