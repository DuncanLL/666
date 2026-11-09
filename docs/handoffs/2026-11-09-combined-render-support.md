# R116 渲染支持 0.3.0

同一工坊项目提供 `Aimoh-Native-GF2` 与 `Aimoh-Eclipse-GF2`。一个 Java MOD 安装两份自带光影，保留已有设置，由 Viewpoint 当前选择决定活动后端。发行保留工坊项目 `3834707439`、内部 MOD ID `AimohGF2Eclipse` 和原预览图。支持的宿主为官方 Viewpoint 0.1.7-rc3。

Eclipse 自定义采样器从 RC3 的常规区间分配，跳过宿主已用槽位，并在提交采样器绑定前核对当前驱动的合并纹理槽上限。GL 状态保存与恢复跳过 Iris 上下文不存在的槽。Native 在进入状态保存前完成能力检查。两套内嵌光影内容与上一候选相同，保留 Eclipse 的雾边界和 TAA 修复。

OpenGL 4.3 规范最低只保证 96 个合并纹理槽。RC3 自带固定绑定表最高到 159，实际是否使用由渲染路径决定。判断目标设备还需核对当前驱动的阶段上限、纹理格式和链接结果。 [Khronos OpenGL 4.3](https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf) · [Khronos 认证列表](https://www.khronos.org/conformance/adopters/conformant-products/opengl) · [NVIDIA OpenGL](https://developer.nvidia.com/opengl) · [Intel 图形 API](https://www.intel.com/content/www/us/en/support/articles/000005524/graphics.html)

## 候选验证

- 正常 ZombieBuddy Java 入口在独立空用户目录完成安装，RC3 的两套发现器都识别到各自光影。自定义光影和原宿主 JAR 保持原字节，工坊候选校验通过。
- AMD Radeon RX 9070 XT 驱动 `26.9.1.260806` 跑通 Native 与 Eclipse 的受控绘制。两个后端各覆盖 13 个模型、48 个着色器组合，并验证材质上传读回。同进程切换 Native → Eclipse → Native 时记录到释放、绘制和重建。
- 同一 Eclipse 夜间暴雨和雾气场景的最高自定义采样槽由 151 降到 123，变更前后的最终截图 SHA-256 一致。TAA 关闭路径也完成驱动绘制。该测试只覆盖固定受控场景，玩家报告的天空或雾闪烁仍待真实场景复验。
- OpenGL GLSL 参考编译和链接通过 Native 85 项、Eclipse 106 项。Native 使用相同宿主与内嵌包指纹的既有展开源码重新编译，当前 JAR 另有实际驱动检查。Eclipse 使用合包的夜雨场景展开源码，覆盖 TAA 两种状态。
- 两份证据均为 `LIMITED_HARDWARE_VALIDATION`。NVIDIA、Intel、真实游戏与 Steam 订阅冷启动未验收。

本地候选在 `build/render-combined-portability/final/Workshop/R116RenderSupport`，JAR SHA-256 为 `9a9462f44da9dd9766b0316849355582ba68ff1be438504a960a2a53a84fd6e0`。构建和校验入口见 [渲染发布标准](../render-release-standard.md)。
