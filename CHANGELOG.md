# 版本记录

当前项目为「少前2追放角色框架」(`GFL2Exilium`)。维护目标是兼容更新、四国语言本地化及复现明确的缺陷。角色、衣装、材质与动作按定版维护。

游戏动作、人物状态和光影接线详见 [维护手册](docs/maintenance/README.md)。

## 0.7.15 · 当前本地维护基线

- VMD 入口默认隐藏。原有触发方式和会话行为见对应发布记录。
- 本地安装和工坊作者包的核心目录统一为 `GFL2Exilium`，JAR 为 `GFL2Exilium.jar`。同步构建、打包与核验的路径。作者包通过 PZ 校验，现装原生组件可加载阿斯缇亚模型。
- 该版本记录为本地安装。Steam 内容版本按下方有发布凭据的条目确认。四国语言开发在自己的工作树继续。

[0.7.15 本地版本记录](provenance/releases/0.7.15-vmd-entry-toggle.json)

## 工坊页面 · 2026-11-17

- 24 个角色使用高清原皮插画头像作为主封面，工坊标题统一为「中文名 - 英文名」。
- 框架简介用 88 × 88 的无文字头像链接连接各角色页，并提供原生订阅合集入口。
- 角色详情相册中的旧目录小头像已撤下。

[框架项目](https://steamcommunity.com/sharedfiles/filedetails/?id=3838709437) · [角色订阅合集](https://steamcommunity.com/sharedfiles/filedetails/?id=3841234557)

## 0.7.14 · 2026-11-17

- 持物附件使用游戏手部姿态和物品的附件定义。重新捕获镜头时，每帧复用该姿态的持物结果。
- 通过握持重放、暂停镜头回归、官方宿主的早加载和后加载检查，以及 RX 9070 XT 的受控出图。
- 框架和秋桦 0.7.2 的 Steam 发布均有内容清单回读。真实游玩验证在该发布记录中仍为待测。

[框架发布及 Steam 回读](provenance/releases/0.7.14-held-slot-attachment.json) · [秋桦 0.7.2](provenance/releases/qiuhua-0.7.2-chest-weights.json)

## 0.7.13 · 2026-11-17

- 适配 Steam 创意工坊正式版 Viewpoint 0.1.7。统一身体物理入口并更新持物姿态的同步。
- 完成人物联机协议、整备室经济和换装逻辑的组件测试，核对框架和本次更新的角色包发布回读。

[0.7.13 记录](provenance/releases/0.7.13-viewpoint-multiplayer.json) · [多人回环验证](provenance/releases/multiplayer-loopback-validation.json)

## 0.7.9–0.7.12

- 从旧宿主版本迁移到 Steam 正式版 Viewpoint 0.1.7，核对点光布局、身体物理入口、握持与兼容性。
- 各阶段的原生组件、宿主与发布状态按独立记录核对。

[0.7.9](provenance/releases/0.7.9-viewpoint-017.json) · [0.7.10](provenance/releases/0.7.10-viewpoint-light-body-physics.json) · [0.7.11](provenance/releases/0.7.11-viewpoint-compatibility-audit.json) · [0.7.12](provenance/releases/0.7.12-viewpoint-held-phase.json)

## 0.7.0–0.7.8 · 历史

- 角色包独立发布，补齐整备室、专武及语音资料，迭代局部软体物理。
- 当时的常规版与 RC4 并行验收属于历史。当前支持范围见 [README](README.md)。

[0.7.0](docs/releases/0.7.0.md) · [0.7.5](docs/releases/0.7.5.md) · [0.7.6](docs/releases/0.7.6.md) · [0.7.7](docs/releases/0.7.7.md) · [0.7.8](docs/releases/0.7.8.md)
