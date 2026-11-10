# 板块与工作树索引

## 按需求找代码

| 板块 | 入口 |
|---|---|
| 人物接入、配件、注册 | [registry.json](../game/mod/42/media/aimoh_pmx/registry.json)、[人物 profile 与配件配置](../game/mod/42/media/aimoh_pmx/models)、[整备室 catalog](../game/mod/42/media/ui/AimohPMXReadyRoom/catalog.json) |
| 动作、持物、姿态 | [ModelSession.java](../game/java/aimoh/pmxpreview/ModelSession.java)、[WorldPoseRetargeter.java](../game/java/aimoh/motion/WorldPoseRetargeter.java)、[Ozz 握持桥](../native/ozz/ozz_grip_bridge.cpp) |
| 头发、衣物、胸部物理 | [PmxModel.java](../game/java/aimoh/motion/PmxModel.java)、[PMX 刚体引擎](../native/mmd-engine/src/physics)、[ChestSoftBody.java](../game/java/aimoh/motion/ChestSoftBody.java)、[胸部体积求解](../native/chest-softbody/softbody.cpp) |
| 整备室 UI 和预览 | [AimohPMXWindow.lua](../game/mod/42/media/lua/client/AimohPMXWindow.lua)、[AimohPMXRoomData.lua](../game/mod/42/media/lua/client/AimohPMXRoomData.lua)、[ReadyRoomPreview.java](../game/java/aimoh/pmxpreview/ReadyRoomPreview.java) |
| 语音、联机 | [语音路由](../game/mod/42/media/lua/shared/AimohPMXVoice/VoiceRouting.lua)、[声音定义](../game/mod/42/media/scripts)、[联机协议](../game/mod/42/media/lua/shared/AimohPMXProtocol.lua)、[服务端入口](../game/mod/42/media/lua/server/AimohPMXNetwork.lua) |
| 人物网格提交与渲染资料 | [PmxDrawMesh.java](../game/java/aimoh/motion/PmxDrawMesh.java)、[PmxOriginalInputs.java](../game/java/aimoh/motion/PmxOriginalInputs.java)、[外置资源清单](../resources/manifest.json) |
| Native 渲染与共用代码 | [渲染 Java](../render-support/java)、[Native 光影源](../render-support/native-pack) |
| Eclipse 渲染与合包 | [Eclipse 覆盖](../render-support/eclipse)、[合包入口源码](../render-support/combined)、[渲染发布标准](render-release-standard.md) |
| 构建、打包、检查 | [常用命令](../README.md#本机使用)、[工具脚本](../tools)、[Java 检查夹具](../tools/java)、[Lua 检查夹具](../tools/tests) |

人物注册、整备室图文和专属渲染资料是三份独立索引。渲染索引 `42/media/aimoh_gf2/characters.json` 和其二进制资料从外置人物资源读取。改变 PMX 或材质槽时要核对三份索引的模型身份和实际文件。

## 当前开发线

| Git 分支 | 用途 |
|---|---|
| `main` | Native／Eclipse 共用接入、后端修复、合包分发，以及人物与原生核心的仓库基线 |
| `codex/r116-render-rc4-20261110` | PZ 42.22／VP RC4 的渲染分发与 Eclipse 遮挡修正。阶段点 `render-support-v0.3.23` |
| `codex/r116-runtime-stability-20261108` | 人物运行时、物理、整备室和游戏动作修正。已提交人物阶段点是 `052649e` |
| `codex/r116-live-vmd-room` | 游戏内舞蹈与整备室动作开发。由人物阶段点 `052649e` 分出，实际代码还需核对该工作树的差异 |

用 `git worktree list` 找目录，再用 `git status --short --branch` 和 `git log -1 --oneline` 核对该处的提交与进行中的改动。现装人物版本从实际加载目录的 `42/mod.info` 和 JAR 指纹确认。当前两条人物开发线与 `main` 的提交合并会在文档、外置资源清单和原程序检查脚本上产生冲突，归并时按职责逐项核对。

## 维护记录

### 人物运行时与界面

- [胸部物理公共接入](chest-softbody-trial.md)
- [STB 误释放与切人物崩溃](texture-release-fix.md)
- [整备室命中区域修复](ui-hit-regions-fix.md)
- [整备室 0.3.4 导航与可读性](ready-room-navigation-0.3.4.md)

### 渲染支持

- [0.3.23 的 RC4 适配、遮挡修正与玩家反馈](handoffs/2026-11-10-render-support-0.3.23.md)
- [Native 与 Eclipse 合包](handoffs/2026-11-09-combined-render-support.md)
- [RC3 接入与回退](handoffs/2026-11-09-rc3-render-stability.md)
- [绘制路径与资源开销对照](handoffs/2026-11-09-render-active-path.md)
- [Eclipse 天气、雾与 TAA 审计](handoffs/2026-11-09-eclipse-weather-audit.md)
- [Eclipse 阶段接口与升采样修复](eclipse-stage-interface-fix.md) · [屏幕映射](eclipse-screen-transport-0.1.2.md)

### 历史基线与交接

- [2026-11-08 仅 Native 的迁移记录](handoffs/2026-11-08-native.md)
- [2026-11-07 阶段交接](handoffs/2026-11-07.md)
- [2026-11-06 两个工坊项目与发布边界](RELEASE-2026-11-06.md)
- [初始仓库迁移基线](BASELINE.md)

原始日志、生成包和本机报告留在 `build`。各阶段记录分别注明受控检查、安装和游戏实测的覆盖范围。
