# 保留的 Eclipse 接入分支

Eclipse 0.1.5 基于已认可的 0.1.2 发行包，面向官方 Viewpoint 0.1.7-rc3。

`java` 保存 Eclipse 的入口、阶段调度和画面缩放/TAA 适配。两边共用宿主校验、骨骼数据准入、顶点阶段接口、描边、材质与原采样器/贴图读取。构建按固定 SHA 读取外置基线，替换清单中的类及 TAA 的人物运动取样片段。其余光影源码逐项核对字节。

本机依赖由 `.local/paths.json` 的 `eclipse_support_reference_jar` 和两边共用的 `viewpoint_jar` 提供，指纹见 `provenance/eclipse-outline.json`。Eclipse 输出在 `build/eclipse-support`。

构建用 `python tools/build_eclipse_support.py --workshop <新候选目录> --metadata-from <现有渲染支持工坊项目>`，验证用 `python tools/check_render_support.py --backend eclipse --project <候选目录>`。候选沿用工坊 ID 和预览图。

原内部MOD ID与Native分发器相同，两种发行候选不能同时启用。需要实际切换/安装时沿用户授权执行并保护工坊元数据、现有设置和回退；不能以复制到另一目录变成两个同时加载的接入层。
