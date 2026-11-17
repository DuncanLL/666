# 保留的 Eclipse 接入分支

Eclipse 分支基于已认可的 0.1.2 发行包，面向官方 Viewpoint 0.1.7-rc4。0.3.0 起同一个 MOD 分发 Eclipse 和 Native，由 Viewpoint 的光影选项切换。「R116 人物效果」中的体表细节和皮肤光泽默认均为 0。0.3.2 为两套光影加入人物描边粗细，范围 0.75–1.35，1.00 为原宽度。

`java` 保存 Eclipse 的入口、阶段调度和画面缩放/TAA 适配。两边共用宿主校验、骨骼数据准入、顶点阶段接口、描边、材质与原采样器/贴图读取。构建按固定 SHA 读取外置基线，替换清单中的类及雾、TAA 三个阶段的定点片段。其余光影源码逐项核对字节。

本机依赖由 `.local/paths.json` 的 `eclipse_support_reference_jar` 和两边共用的 `viewpoint_jar` 提供，指纹见 `provenance/eclipse-outline.json`。Eclipse 输出在 `build/eclipse-support`。

用 `python tools/build_eclipse_support.py` 生成合包输入。发行构建及验证步骤见 [渲染发布标准](../../docs/render-release-standard.md)。单独的 Eclipse 构建可供后端诊断，合包沿用工坊 ID 和预览图。
