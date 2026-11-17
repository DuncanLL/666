# 保留的 Eclipse 接入分支

Eclipse 继承 0.1.2 的已验证构建输入，当前宿主是 Steam 创意工坊正式 Viewpoint 0.1.7。框架合包提供 Eclipse 和 Native，在游戏中由 Viewpoint 选择。当前运行、着色阶段与兼容性排查见 [光影维护手册](../../docs/maintenance/rendering.md)。

`java` 保存 Eclipse 的入口、阶段调度和画面缩放/TAA 适配。两边共用宿主校验、骨骼数据准入、顶点阶段接口、描边、材质与原采样器/贴图读取。构建按固定 SHA 读取外置基线，替换清单中的类及雾、TAA 三个阶段的定点片段。其余光影源码逐项核对字节。

本机依赖由 `.local/paths.json` 的 `eclipse_support_reference_jar` 和两边共用的 `viewpoint_jar` 提供，指纹见 `provenance/eclipse-outline.json`。Eclipse 输出在 `build/eclipse-support`。

用 `python tools/build_eclipse_support.py` 生成合包输入。发行构建及验证步骤见 [渲染发布标准](../../docs/render-release-standard.md)。单独的 Eclipse 构建可供后端诊断，合包沿用工坊 ID 和预览图。
