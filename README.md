# 僵毁少女前线

已在 Steam 创意工坊正式发布。当前框架 **0.7.31**，内部 MOD ID 为 `GFL2Exilium`。25 个角色包共登记 72 个造型，按需订阅。正式框架分发 Native 和 Somber，Eclipse 保留在维护构建中。当前核对的环境为 Project Zomboid 42.22.2、官方 Project Viewpoint 0.1.7 和 ZombieBuddy 2.3.4。

- [框架工坊项目](https://steamcommunity.com/sharedfiles/filedetails/?id=3842318650)
- [25 个角色包与框架的订阅合集](https://steamcommunity.com/sharedfiles/filedetails/?id=3841234557)
- [版本记录](CHANGELOG.md)
- [0.7.31 线上清单、框架 JAR 指纹与角色更新记录](provenance/releases/0.7.31-workshop-public.json)
- [维护手册](docs/maintenance/README.md) · [源码导航](docs/README.md) · [光影发布检查](docs/render-release-standard.md)
- [从 PMX 标定、原材质接入到 Native / Eclipse 合包的实作流程](docs/maintenance/pmx-import-workflow.md)

## 本机使用

从 [路径模板](config/paths.example.json) 创建 `.local/paths.json`，填写实际游戏、ZombieBuddy、Viewpoint、编译工具和外置人物资源位置。模型、贴图、语音、生成包与原始日志留在本机。版本核对以工作区提交、实际 `mod.info` 和产物指纹为准。

人物核心由 `tools/build.py` 构建。使用 `tools/package_workshop.py` 准备人物输入，用 `tools/build_combined_render_support.py` 合并渲染后端输入，最终由 `tools/package_gfl2_unified.py` 生成框架工坊候选。发布目录采用 `Workshop/GFL2Exilium`，保留现有工坊项目 ID。工具的参数和输入检查以源码为准。

## 维护范围

角色与功能按定版维护，日常工作集中在游戏和光影适配、四国语言本地化及复现明确的缺陷。版本记录分别注明组件检查、现装版本、Steam 发布和实机验证。
