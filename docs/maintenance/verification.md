# 维护与验证

人物进入定版维护以后，通常只处理受影响的公共链路。每个候选保持可重建的源码提交、宿主指纹、依赖、配置和外置资源清单。构建前先核当前工作树和实际发放版本，确认本机、工坊作者目录和订阅端分别使用的是哪份产物。

## 版本适配按影响层验收

| 改动 | 优先核对 | 代表场景 |
|---|---|---|
| PZ 或 ZombieBuddy 小版本 | 游戏 Java 接口、Lua 事件、模块授权、ID 解析、旧存档和会话生命周期。 | 新存档或订阅冷启动，原旧存档恢复，首次启用、换人和换装、回主菜单后再次进入。 |
| PZ 动作和第一人称改动 | AnimationPlayer 矩阵、骨名／索引、混合掩码、实际持物槽位、座位和相机锚点。 | 站走跑、蹲、翻窗／跨低栏、近战和持枪、进出车辆。第一人称和第三人称分别看，暂停时绕人物检查持物。 |
| 官方 Viewpoint 改动 | 真实 JAR 和签名、被挂接的方法及布局、帧采样 phase、目标格式、灯光记录和资源退役接口。 | Native 和 Eclipse 各测白天、夜晚、室内手电、透明发丝、眼睛、实面和阴影。切到别的光影并切回来，重复切角色，检查网格池和帧历史。 |
| 某一衣装或角色资源 | 模型 ID、PMX SHA、角色包配置、真实材质槽、UI 分片、源程序和贴图指纹。 | 改到的衣装和相关共享变体，从启用、换装到退出。只在特定动作复现时跑完整动作和进入／退出混合。 |
| 本地化、语音文本 | 翻译键、回退语言、角色包翻译与 cue 键。 | 整备室菜单、人物资料、配件和接入用语。语音分别查触发、字幕和实际音频。 |

资产校验尽量做全量，实机矩阵按受影响的接口选用例。共同姿态适配优先选一套复杂裙摆、一套有独立局部物理和多配件的衣装。着色适配优先覆盖透明、眼睛和明显描边。代表角色只限定本轮测试范围，回归来自公共代码时应扩大资产与场景检查。

## 已有的检查入口

| 想确认 | 用哪个入口 | 得到什么 |
|---|---|---|
| 目录、ID 和产物 | [check_runtime_locations.py](../../tools/check_runtime_locations.py)、[check_runtime.py](../../tools/check_runtime.py) | 已配置安装路径和 JAR 读回、工坊候选校验、原生组件及模型身份。脚本依赖该工作树的候选与本机配置。 |
| 动作源与帧推进 | [check_source_pose.py](../../tools/check_source_pose.py)、[check_frame_timing.py](../../tools/check_frame_timing.py) | 实际 PZ 动画组件、姿态守护和受控帧推进。源姿态夹具需要对应的本机依赖和已知动作输入，按脚本开头和参数准备。 |
| 武器与镜头 | [GripPhaseCheck.java](../../tools/java/GripPhaseCheck.java)、[GripPauseCheck.java](../../tools/java/GripPauseCheck.java) | 持物与手部的相同插值权重、暂停和重捕获、换物品、相机切换及握持参数。既有对照见 [0.7.12](../../provenance/releases/0.7.12-viewpoint-held-phase.json) 和 [0.7.14](../../provenance/releases/0.7.14-held-slot-attachment.json)。 |
| 切人、隐藏部件和退役 | [check_lifecycle.py](../../tools/check_lifecycle.py)、[check_physics_streaming.py](../../tools/check_physics_streaming.py)、[check_texture_runtime.py](../../tools/check_texture_runtime.py) | 候选交接、发布外观前的就绪检查、图形资源释放、阴影专用部件以及旧新帧历史。 |
| 整备室和多人 | [check_room_workshop.py](../../tools/check_room_workshop.py)、[check_room_economy.py](../../tools/check_room_economy.py)、[check_multiplayer.py](../../tools/check_multiplayer.py) | PZ 自带 Kahlua 执行生产 Lua，覆盖购买、领取、重试、联机槽位、恢复和语音生命周期。这些脚本的游戏对象与网络送达使用受控输入。 |
| 工坊合包与渲染 | [check_r116_unified.py](../../tools/check_r116_unified.py)、[check_render_support.py](../../tools/check_render_support.py)、[check_render_release.py](../../tools/check_render_release.py) | 合包的实际启动和 pack 发现、宿主展开语法及设备证据。按 [渲染发布标准](../render-release-standard.md) 分别记录 Native 和 Eclipse。 |
| 分发内容 | [check_release_leaks.py](../../tools/check_release_leaks.py)、[外置文件清单](../../resources/manifest.json) | 发行件与明确参考输入比较。定位官方宿主内容、未经许可的资源及不应进入发布包的文件。 |

常用组件检查可以在仓库根目录按以下方式跑。带路径的命令用本次实际候选替换尖括号里的值。

```powershell
python tools/check_room_workshop.py
python tools/check_room_economy.py
python tools/check_multiplayer.py
python tools/generate_multiplayer_catalog.py --check
python tools/check_lifecycle.py --jar "<人物候选 JAR>"
python tools/check_runtime_locations.py --jar "<已验证的人物 JAR>"
python tools/check_r116_unified.py --project "<候选目录>/Workshop/GFL2Exilium"
python tools/check_render_release.py --evidence "<后端验证记录 JSON>" --out "<结果 JSON>"
```

## 出现回归时记录什么

每个报告最少给出版本与指纹、角色和衣装、触发状态、实际结果、应有结果，以及验证了哪一层。会改变状态的操作还应描述进入和退出，例如从原版启用角色、暂停旋转视角后恢复、或从 Native 切换到 Eclipse 再切回来。日志先保留首个报错以及对应帧和源文件，便于判断后面的错误是不是连锁反应。

- **读档后外观恢复不对**，记录保存键、模型 ID、实际角色包版本和被拒绝的外观字段。联机另记录会话和客户端／服务器顺序。
- **动作或握持异常**，记录 PZ 动作名及混合状态、视角、拿在左右哪只手、物品和配件、是否校准过、是否触发了暂停或镜头切换。收集对应的源骨骼和最终矩阵。
- **光影效果或性能异常**，记录实际选择和绘制的 pack、宿主及 JAR 指纹、GPU 和驱动、昼夜与光源、错误 pass，以及是否在换包或切角色后出现。
- **整备室或语音异常**，记录本地会话与网络就绪、实际衣装、当前 UI 的数据来源、触发 cue 及听者是谁。字幕／音频索引与画面选择可以各自出错。

## 发布和回退

1. 以已核对的正式源码和真实宿主重建候选，使用 [README](../../README.md#本机使用) 的构建入口。保留已发布工坊项目的 `workshop.txt`、封面和身份。候选目录用 `Workshop/GFL2Exilium` 的结构，发布前确认没有旁支的旧 MOD ID 或重复入口。
2. 准备候选时保留将被替换的 JAR、元数据和源资源的指纹。回退按改动的组件成组恢复，例如光影更新需要同时对应宿主、核心接口和两份内嵌 pack。切人物的回退会涉及真实加载位置和注册表。
3. 按版本记录静态验证、实际组件／宿主检查、真实安装指纹、工坊内容清单和实机验证。Steam 内容版本通过内容清单回读，不能直接从 Git 提交或作者目录推断。
4. AMD 上的宿主检查只覆盖该设备和当时驱动。公开做三家显卡兼容声明时，按 [发布标准](../render-release-standard.md) 取得 AMD、NVIDIA 和 Intel 各自的结果。发布验证的 `--public` 关口见相应脚本。

## 当前证据怎样解释

[0.7.14 发布回读](../../provenance/releases/0.7.14-held-slot-attachment.json) 与 [0.7.15 本地记录](../../provenance/releases/0.7.15-vmd-entry-toggle.json) 分开。旧报告中的 RC4、旧项目编号、旧材质标记和已完成的候选，都保留当时的参考范围。换宿主、包、版本、硬件或影响层以后，应以当前来源重新取证。早期出现过的原生切换崩溃、真实双客户端画面与语音、订阅冷启动，分别记录实测状态。
