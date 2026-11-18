# 维护手册

本文档以 0.7.15 源码作为当前维护基线。[版本记录](../../CHANGELOG.md) 区分本地现装和已回读的 Steam 内容版本。适配的游戏分支为 PZ B42.22，光影使用已核对指纹的 Steam Viewpoint 0.1.7。24 个角色包按同一人物、整备室与渲染契约加载。

| 要查的问题 | 入口 |
|---|---|
| 从裸 PMX 制作一套衣装并复核结果 | [导入与标定](pmx-import-workflow.md) · [原材质与三角形映射](source-material-workflow.md) · [双后端构建和验收](workflow-build-checks.md) |
| 走跑蹲、翻窗跨栏、空手、持枪与瞄准、第一人称、车内、暂停和 VMD | [游戏中的动作、持物和物理](gameplay.md#各种游戏状态) |
| 换人失败、角色重载、姿态异常、刚体和胸部软体 | [模型会话](gameplay.md#从游戏角色到模型会话) · [异常恢复](gameplay.md#两个需要保留的异常恢复) |
| 角色包、整备室、招募和专武、保存、联机握手、语音状态 | [人物数据、整备室、联机与语音](character-systems.md) |
| 光影安装、材质接入、Native、Somber、Eclipse、透明和阴影、切包退役 | [Viewpoint、Native、Somber 与 Eclipse 光影](rendering.md) |
| 适配版本、选验证用例、工坊打包、回退及旧证据边界 | [维护与验证](verification.md) |

以前的修正和失败证据留在[版本记录](../../CHANGELOG.md)、[发布凭据](../../provenance/releases)和各章链接中。当前行为以源码、实际目标版本和现用输入为准。源码和资源文件之间的角色 ID、包键、PMX 指纹、Shader 记录格式及存档字段属于跨版本契约。
