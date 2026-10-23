# 文档目录

本目录用于存放项目级文档，包括测试记录、部署说明、接口调试记录、故障排查记录和其他过程材料。

现有文档：

- [ROADMAP.md](./ROADMAP.md)：功能路线图和进度
- [skills.md](./skills.md)：内置技能说明
- [pdf-parsing.md](./pdf-parsing.md)：PDF 解析说明
- [test/README.md](./test/README.md)：测试用例目录、记录规范与最近测试结果
- [bugs/README.md](./bugs/README.md)：bug 现象、原因和解决方式的简短记录

联网搜索可在 `application-local.yml` 配置：`search-api.provider` 使用 `searchapi` 或 `tavily`，`search-api.api-key` 填对应服务的密钥。

建议分类：

- `bugs/`：单个 bug 的纯文本排查记录
- `test/`：测试方案、测试用例和回归记录
- `ops/`：启动、部署、配置和故障排查文档
- `api/`：接口调试记录和调用示例
- `notes/`：临时技术记录和决策说明

架构、模块边界和长期设计文档统一放在项目根目录的 `docs/design/`。文档统一放在 `docs/`，不要再在根目录新建平级文档目录。
