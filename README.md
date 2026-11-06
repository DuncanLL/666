# R116

Project Zomboid B42.21 游戏人物 MOD 的私有维护仓库。代码与构建发布流程从当前 R116 工坊候选整理而来，工作台不在本仓库内。

## 目录

| 目录 | 内容 |
|---|---|
| `game/java` | 游戏入口、动作映射、绘制、生命周期、原生库启动 |
| `game/mod` | 模组信息、Lua、整备室资料、声音定义和人物配置 |
| `native/mmd-engine` | 当前 MC-MMD 上游派生的原生引擎，包含固定的 PMX parser/Bullet 源码 |
| `native/ozz` | 现用 Ozz 握持求解与薄 C 接口 |
| `tools` | 构建、工坊候选生成与组件检查 |
| `resources/manifest.json` | 仓库外人物素材的相对路径、大小、SHA-256 |
| `provenance`、`licenses` | 来源、上游版本、导入记录和第三方许可 |
| `.local`、`build` | 仅本机路径和生成结果，Git 忽略 |

## 本机使用

本机 `.local/paths.json` 已配置；在另一台制作电脑上先复制 `config/paths.example.json` 并填写依赖位置。需要已有合法游戏依赖、ZombieBuddy、Viewpoint、Java 25 运行时、ECJ 4.38，以及现有外部人物资源。第三方游戏和工具二进制不随仓库分发。

```powershell
python tools/build.py
python tools/package_workshop.py
python tools/check_runtime.py
```

默认工坊候选在 `build/workshop/R116`。脚本不自动覆盖已有输出或安装目录，也不会启动游戏或执行 Steam 上传。输出包含完整人物资料；人物文件从本机资源目录读回，不需要下载 GitHub 仓库的玩家参与构建。

已有候选需要复查时，运行 `python tools/package_workshop.py --verify-existing`，只读回核对当前代码与资源，不再复制整套素材。

首次迁移基准使用 `python tools/build.py --compare-baseline`：完整编译游戏源码，与迁移前工坊 JAR 比较，忽略编译调试属性。功能修改后的普通构建允许产生预期差异。

默认发布沿用已验证原生库的指纹。`python tools/build_native.py`、`python tools/build_native.py --ozz` 可分别生成原生候选，使用工程私有工具链；不会自动更新现用库或基准指纹。原生候选必须经过对应回归后才能进入发布资料。

## 日常维护

1. 在 GitHub Desktop 的 Changes 查看本次差异。
2. 修改并运行相关检查。源码、配置与本机资源清单要对应同一版本。
3. 记录清楚的提交，推送到私有仓库。
4. 需要发测试版时生成工坊候选，确认后再安装或更新作者项目；保留已有工坊 ID。

构建记录包含源码提交号与未提交状态。人物模型、贴图、语音、光影大资源、工作台、工具链、测试游戏及历史输出不提交。

## 当前验收边界

迁移基准的实际结果见 `docs/BASELINE.md`。游戏程序和已装 MOD 没有因整理仓库而修改。组件检查不代表游戏实测或多人验收；切人物原生崩溃尚未确认根治。妮基塔入口隐藏，原资源保留。

第三方许可分别保留，本仓库没有把游戏人物素材或全部第三方代码声明为我们原创；根目录未擅自选择覆盖全部内容的新许可证。

