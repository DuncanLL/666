# R116

Project Zomboid B42.21 的 R116 人物 MOD 和配套渲染支持。两者是独立的工坊项目。渲染支持用一个 MOD 分发 Native 和 Eclipse，由玩家在官方 Viewpoint 0.1.7-rc3 中选择。工作台独立维护。

接新需求时先看[板块与工作树索引](docs/README.md)，构建和安装前以目标工作树、候选指纹和实际装载位置为准。

## 目录

| 目录 | 内容 |
|---|---|
| [game/java](game/java) | 人物会话、动作映射、握持、网格提交和 JNI 入口 |
| [game/mod](game/mod) | 游戏内 Lua、整备室、多人协议、声音定义和人物配置 |
| [native](native) | PMX 与 Bullet 刚体引擎、胸部体积解算、Ozz 握持求解 |
| [render-support](render-support) | Native 与公共渲染源码、Eclipse 覆盖和双后端合包入口 |
| [tools](tools) | 构建、工坊候选、组件检查及 Java／Lua 检查夹具 |
| [resources/manifest.json](resources/manifest.json) | 外置人物素材的相对路径、大小与 SHA-256 |
| [config](config)、[provenance](provenance)、[licenses](licenses) | 本机配置模板、来源与构建基线、第三方许可 |
| [docs](docs)、[experiments](experiments) | 维护记录和独立实验 |
| `.local`、`build` | Git 忽略的本机配置和可重新生成的输出 |

## 本机使用

从 [config/paths.example.json](config/paths.example.json) 复制出 `.local/paths.json`，填写游戏、ZombieBuddy、Viewpoint、Java 25、ECJ 4.38 和外置人物素材的位置。路径和资源基线按工作树核对。

### 人物 MOD

以下是本工作树胸部物理候选的构建和组件检查入口。

```powershell
python tools/build_chest_trial.py
python tools/check_runtime.py build/workshop/chest-trial/R116
```

只改 Java 并复用已验证的原生候选时，可传 `tools/build.py --native-candidate build/native/game-candidate.json`。直接运行 `tools/build.py` 会读取历史原生基准。`--compare-baseline` 用于历史迁移的类文件对照。功能改动按影响范围检查。`tools/package_workshop.py` 的默认输出为 `build/workshop/R116`。

### 渲染支持

先生成两个后端的构建输入，再合成一个工坊候选。打包时用 `--metadata-from` 读取现有作者项目的元数据与预览图，保留项目 `3834707439` 和内部 ID `AimohGF2Eclipse`。

```powershell
python tools/build_render_support.py
python tools/build_eclipse_support.py
python tools/build_combined_render_support.py
python tools/package_render_support.py --metadata-from "<现有作者项目目录>" --output "<新的候选目录>"
python tools/check_render_support.py --project "<新的候选目录>"
```

人物原游程序、贴图及属性数据由人物 MOD 的 `media/aimoh_gf2` 提供。渲染支持提供两个光影包和共用的宿主接入。来源和基线见 [Native](provenance/render-support.json) 与 [Eclipse](provenance/eclipse-outline.json)，验证要求见[渲染发布标准](docs/render-release-standard.md)。

## 日常维护

用 `git worktree list` 和 `git status --short --branch` 定位任务所在的源码和已有差异。单独记录本次改动及对应验证。人物模型、贴图、语音、原游着色资源、工具链和本机生成物留在 Git 外。

候选构建、组件检查、安装、真实游戏验收和工坊上传分别记录。现装 MOD 可能来自另一工作树，版本号由对应的 `mod.info` 和产物记录核对。
