# 僵毁少女前线

Project Zomboid B42.22 的少前2角色 MOD。核心内部 ID 为 `GFL2Exilium`，随包提供 Native 和 Eclipse 人物渲染支持，当前维护基线使用 Steam 创意工坊提供的 Project Viewpoint 0.1.7。

- [框架工坊项目](https://steamcommunity.com/sharedfiles/filedetails/?id=3838709437)
- [24 个角色包的订阅合集](https://steamcommunity.com/sharedfiles/filedetails/?id=3841234557)
- [版本记录](CHANGELOG.md)
- [维护手册](docs/maintenance/README.md) · [源码导航](docs/README.md) · [光影发布检查](docs/render-release-standard.md)

## 本机使用

从 [路径模板](config/paths.example.json) 创建 `.local/paths.json`，填写实际游戏、ZombieBuddy、Viewpoint、编译工具和外置人物资源位置。模型、贴图、语音、生成包与原始日志留在本机。版本核对以工作区提交、实际 `mod.info` 和产物指纹为准。

人物核心由 `tools/build.py` 构建。使用 `tools/package_workshop.py` 准备人物输入，用 `tools/build_combined_render_support.py` 合并渲染后端输入，最终由 `tools/package_gfl2_unified.py` 生成框架工坊候选。发布目录采用 `Workshop/GFL2Exilium`，保留现有工坊项目 ID。工具的参数和输入检查以源码为准。

## 维护范围

角色与功能按定版维护，日常工作集中在游戏和光影适配、四国语言本地化及复现明确的缺陷。版本记录分别注明组件检查、现装版本、Steam 发布和实机验证。
