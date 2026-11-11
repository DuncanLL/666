# R116

Project Zomboid 42.22.2 的 **R116 人物与光影单 MOD**。当前工坊版本为 **0.3.46**，项目 `3834454638`，游戏内 ID `AimohPmxPreview`。同一个 JAR 分发 Native 和 Eclipse，玩家在 Steam 工坊正式版 Viewpoint **0.1.6.2** 中选择。独立角色包按需启用。 [当前发布记录](docs/handoffs/2026-11-11-r116-public-pack-hygiene-046.md)

接新需求时先看[板块与工作树索引](docs/README.md)，构建和安装前以目标工作树、候选指纹和实际装载位置为准。

## 目录

| 目录 | 内容 |
|---|---|
| [game/java](game/java) | 人物会话、动作映射、握持、网格提交和 JNI 入口 |
| [game/mod](game/mod) | 游戏内 Lua、整备室、多人协议、声音定义和人物配置 |
| [game/packs](game/packs) | 五个可选角色包的配置、注册和专用语音脚本 |
| [native](native) | PMX 与 Bullet 刚体引擎、胸部体积解算、Ozz 握持求解 |
| [render-support](render-support) | Native 与公共渲染源码、Eclipse 覆盖和双后端合包入口 |
| [tools](tools) | 构建、工坊候选、组件检查及 Java／Lua 检查夹具 |
| [resources/manifest.json](resources/manifest.json) | 外置人物素材的相对路径、大小与 SHA-256 |
| [config](config)、[provenance](provenance)、[licenses](licenses) | 本机配置模板、来源与构建基线、第三方许可 |
| [docs](docs)、[experiments](experiments) | 维护记录和独立实验 |
| `.local`、`build` | Git 忽略的本机配置和可重新生成的输出 |

## 本机使用

从 [config/paths.example.json](config/paths.example.json) 复制出 `.local/paths.json`，填写游戏、ZombieBuddy、Viewpoint、Java 25、ECJ 4.38 和外置人物素材的位置。路径和资源基线按工作树核对。

### 人物运行时构建输入

构建人物 JAR 时，把已验证的原生候选交给 `tools/build.py --native-candidate <原生候选清单>`。`tools/package_workshop.py` 用 `game/mod` 和外置资源清单生成合包所需的人物输入。`resources/manifest.json` 的 `files` 是当前核心的外置资源，`character_packs` 分别记录五个可选包的外置资源。旧胸部物理候选的构建入口见各自的历史记录。

### 人物与双光影单 MOD 发布

先生成两套光影构建输入并封装，再与已验证的人物目录合并。光影构建的 `reference_jar` 使用相同源码线的人物运行时。光影构建的独立目录用来生成合包输入。游戏发布件是最后一步产出的 `Workshop/R116`。

```powershell
python tools/build_render_support.py
python tools/build_eclipse_support.py
python tools/build_combined_render_support.py
python tools/protect_render_release.py --proguard-zip "<固定版本 ProGuard ZIP>" --metadata-from "<光影构建元数据目录>" --output "<封装构建目录>"
python tools/package_r116_unified.py --character-project "<已验证的人物 Workshop/R116>" --character-sha256 "<人物 JAR SHA-256>" --render-project "<封装光影工坊输入>" --render-report "<protection-report.json>" --version 0.3.46 --output "<新输出目录>/Workshop/R116"
python tools/check_r116_unified.py --project "<新输出目录>/Workshop/R116"
```

人物原游程序、贴图及属性数据由 `media/aimoh_gf2` 提供。合包沿用项目 `3834454638`，并保留用户的工坊元数据。早期独立光影工坊项目 `3834707439` 的资料属于历史记录。验证要求见[渲染发布标准](docs/render-release-standard.md)。

## 日常维护

用 `git worktree list` 和 `git status --short --branch` 定位任务所在的源码和已有差异。单独记录本次改动及对应验证。人物模型、贴图、语音、原游着色资源、工具链和本机生成物留在 Git 外。

候选构建、组件检查、安装、真实游戏验收和工坊上传分别记录。现装 MOD 可能来自另一工作树，版本号由对应的 `mod.info` 和产物记录核对。
