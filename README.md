# R116

Project Zomboid B42.21 游戏人物 MOD 的私有维护仓库。代码与构建发布流程从当前 R116 工坊候选整理而来，工作台不在本仓库内。

**当前接续入口：[0.3.7 Native人物资料](docs/native-characters-0.3.7.md)。芙玲/春田资料及公共DXT5读取已差分安装到 F/Steam，人物核心和新版胸部物理保持0.3.6不变。** 真实游玩、长发持续掉帧及反馈方现场结果仍未确认，详细证据见[维护记录索引](docs/README.md)。

## 目录

| 目录 | 内容 |
|---|---|
| `game/java` | 游戏入口、动作映射、绘制、生命周期、原生库启动 |
| `game/mod` | 模组信息、Lua、整备室资料、声音定义和人物配置 |
| `native/mmd-engine` | 当前 MC-MMD 上游派生的原生引擎，包含固定的 PMX parser/Bullet 源码 |
| `native/ozz` | 现用 Ozz 握持求解与薄 C 接口 |
| `render-support` | 冻结的 Eclipse 渲染接入、自动分发入口及发布默认值 |
| `tools` | 构建、工坊候选生成与组件检查 |
| `resources/manifest.json` | 仓库外人物素材的相对路径、大小、SHA-256 |
| `provenance`、`licenses` | 来源、上游版本、导入记录和第三方许可 |
| `.local`、`build` | 仅本机路径和生成结果，Git 忽略 |

## 本机使用

本机 `.local/paths.json` 已配置；在另一台制作电脑上先复制 `config/paths.example.json` 并填写依赖位置。需要已有合法游戏依赖、ZombieBuddy、Viewpoint、Java 25 运行时、ECJ 4.38，以及现有外部人物资源。第三方游戏和工具二进制不随仓库分发。

当前含新胸部物理的完整候选使用已有构建入口；只改Java且复用已验证的新原生候选时，可以用 `tools/build.py --native-candidate build/native/game-candidate.json`。裸 `tools/build.py` 默认仍读取历史原生基准，不作为本轮胸部物理发布构建命令。

```powershell
python tools/build_chest_trial.py
python tools/check_runtime.py build/workshop/chest-trial/R116
```

Eclipse 配套工坊项目独立生成，名称为 `R116 渲染支持 光影分发`，内部 ID 保留 `AimohGF2Eclipse`：

```powershell
python tools/build_render_support.py
python tools/package_render_support.py
python tools/check_render_support.py
```

光影和原有完整渲染代码来自 `provenance/render-support.json` 冻结输入。ZIP 封装于 JAR，正常 Java MOD 启动后安装到真实玩家目录，再由玩家在 VP 选择；无需修改 VP 文件或写作者配置。两份发布记录见 `docs/RELEASE-2026-11-06.md`。

当前人物候选在 `build/workshop/chest-trial/R116`，独立光影候选在 `build/workshop/R116RenderSupport`；基础 `package_workshop.py` 的默认输出仍是 `build/workshop/R116`。脚本不会启动游戏或执行 Steam 上传，安装/作者项目提升使用独立命令。输出包含完整人物资料；人物文件从本机资源目录读回，不需要下载 GitHub 仓库的玩家参与构建。

已有本轮人物候选需要复查时，运行 `python tools/package_workshop.py --output build/workshop/chest-trial/R116 --verify-existing`，只读回核对当前代码与资源，不再复制整套素材。

首次迁移基准使用 `python tools/build.py --compare-baseline`：完整编译游戏源码，与迁移前工坊 JAR 比较，忽略编译调试属性。功能修改后的普通构建允许产生预期差异。

默认发布沿用已验证原生库的指纹。`python tools/build_native.py`、`python tools/build_native.py --ozz` 可分别生成原生候选，使用工程私有工具链；不会自动更新现用库或基准指纹。原生候选必须经过对应回归后才能进入发布资料。

## 日常维护

1. 在 GitHub Desktop 的 Changes 查看本次差异。
2. 修改并运行相关检查。源码、配置与本机资源清单要对应同一版本。
3. 记录清楚的提交，推送到私有仓库。
4. 需要发测试版时生成工坊候选，确认后再安装或更新作者项目；保留已有工坊 ID。

构建记录包含源码提交号与未提交状态。人物模型、贴图、语音、光影大资源、工作台、工具链、测试游戏及历史输出不提交。

## 当前验收边界

迁移基准的历史结果见 `docs/BASELINE.md`。当前妮基塔入口已恢复，用户已认可新胸部物理的实际游戏效果；0.3.1 修复了已复现的 STB 图片末尾地址误释放，见 `docs/texture-release-fix.md`。受控回归、安装和用户认可分别记录，不能外推所有历史崩溃、多人或真实订阅冷启动均已通过。

第三方许可分别保留，本仓库没有把游戏人物素材或全部第三方代码声明为我们原创；根目录未擅自选择覆盖全部内容的新许可证。

