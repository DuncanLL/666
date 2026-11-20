# 维护手册

从 `main` 接手。2026-11-20 核对的公开版本是 **0.7.31**，框架条目 **3842318650**，25 个角色包共登记 72 个造型。框架与角色包各有自己的版本号。旧框架条目 3838709437 仅存草稿，内部 ID 同为 `GFL2Exilium`，排查时以游戏日志中的实际加载路径识别。[本次发布回执](../../provenance/releases/0.7.31-workshop-public.json) · [现行工坊条目索引](../../provenance/workshop-items.json)

1. 核实版本时，先用发布回执的 `framework_manifest` 对 Steam 内容清单，用 `framework_jar_sha256` 对订阅副本。Git 保存源码、资源指纹和回执。模型、原材质、贴图、语音及构建工具链按[构建手册](workflow-build-checks.md)从外部读取。
2. 复核完整订阅目录可运行 `py -3 tools/check_subscribed_release.py "<Steam 库>/steamapps/workshop/content/108600"`。它只读核对框架和角色包，要求本机已下载整套合集。修改源码后验证候选的入口见[维护与验证](verification.md)。
3. 0.7.31 的整备室工坊按钮映射收录了 24 个角色，缺秋桦。她的分包已发布（3838935753），可以从合集订阅。验证整套发行以工坊索引为准；修整备室入口时核对 `roomWorkshopItems`。
4. 当前待定位的玩家反馈是光影目录中没有生成两套包。先按[分发排查](rendering.md#两个光影目录都没有生成)读当次启动日志。NVIDIA／Intel 的本版实机、真实双客户端表现和旧有的原生切人崩溃仍各需独立证据。

| 要查的问题 | 入口 |
|---|---|
| 从裸 PMX 制作一套衣装并复核结果 | [导入与标定](pmx-import-workflow.md) · [原材质与三角形映射](source-material-workflow.md) · [双后端构建和验收](workflow-build-checks.md) |
| 走跑蹲、翻窗跨栏、空手、持枪与瞄准、第一人称、车内、暂停和 VMD | [游戏中的动作、持物和物理](gameplay.md#各种游戏状态) |
| 换人失败、角色重载、姿态异常、刚体和胸部软体 | [模型会话](gameplay.md#从游戏角色到模型会话) · [异常恢复](gameplay.md#两个需要保留的异常恢复) |
| 角色包、整备室、招募和专武、保存、联机握手、语音状态 | [人物数据、整备室、联机与语音](character-systems.md) |
| 光影安装、材质接入、Native、Somber、Eclipse、透明和阴影、切包退役 | [Viewpoint、Native、Somber 与 Eclipse 光影](rendering.md) |
| 适配版本、选验证用例、工坊打包、回退及旧证据边界 | [维护与验证](verification.md) |

以前的修正和失败证据留在[版本记录](../../CHANGELOG.md)、[发布凭据](../../provenance/releases)和各章链接中。当前行为以源码、实际目标版本和现用输入为准。源码和资源文件之间的角色 ID、包键、PMX 指纹、Shader 记录格式及存档字段属于跨版本契约。
