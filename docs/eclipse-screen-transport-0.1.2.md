# 光影分发0.1.2：升采样选项下人物异常放大

用户提供 `console (3).txt` 和开关光影对照：NVIDIA4060 Laptop 已加载0.1.1，原先的ATTR2链接错误未重现，但启用Eclipse后人物看起来显著放大。日志没有打印当时的TAA升采样配置，所以不能仅凭该日志证明唯一根因，也不能把显卡型号当作模型比例变化的原因。

## 找到的确定缺口

现用Eclipse在 `dimensions/all_solid.vsh`、透明、天空等顶点程序中，当TAA与TAA_UPSCALING开启时执行：

```glsl
gl_Position.xy = gl_Position.xy * RENDER_SCALE + RENDER_SCALE * gl_Position.w - gl_Position.w;
```

它把世界几何放进完整目标内较小的区域，随后升采样回屏幕。原人物程序一直直接输出完整裁剪空间位置，仅添加TAA抖动，因此没有遵守相同的屏幕布局。比例0.5时，人物相对背景可被多放大约2倍；其它比例产生1/scale的表观尺寸差异。低画质预设会开启这条分支，默认关闭此选项的本机常规检查没有覆盖该情况。

生产0.1.1顶点适配代码与包内原场景公式已通过隐藏OpenGL变换反馈对照，在AMD9070XT同样复现：0.5时旧人物宽度比约2.00000015。修后12组比例/抖动组合全部与原场景公式输出一致，最大裁剪坐标误差0，0.5时宽度比1。该检查运行实际生产顶点末段和实际GLSL辅助函数，没有启动游戏或绘制截图，也不是NVIDIA真实游戏验收。

## 公共修复

- `AimohGf2Screen` 根据当前包的TAA、TAA_UPSCALING、SCALE_FACTOR求实际比例。未开启时比例1；原人物几何在抖动前执行与场景相同的裁剪空间映射。
- 早期深度/法线、后期颜色共用该映射；原阴影使用比例1，不将升采样挪到阴影相机。原物体/相机/骨骼矩阵和PMX数据保持不变。
- 原程序 `_ScreenParams/_ScaledScreenParams` 使用实际缩放区域尺寸；车窗原生附加绘制复用同一映射，避免普通场景已缩放而附加几何仍占完整区域。
- 不按角色、GPU、动作或物品分支，不关闭用户升采样，不修改作者/玩家设置，不新增渲染Pass、模型或纹理。新增一个缩放uniform，复用现有每程序/帧设置位置。
- 每个已有渲染会话创建时记录一次TAA开关及有效比例，便于后续判断反馈方实际选项；没有每帧日志或轮询。

接口背景：[Khronos glViewport参考](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glViewport.xhtml)。本修复匹配现用包自己的裁剪空间公式，不改变OpenGL的视口规则。

## 验证及边界

`python tools/check_eclipse_screen.py --before --jar <旧0.1.1>` 可重现旧缺口；`python tools/check_eclipse_screen.py` 检查当前输出。报告在 `build/eclipse-screen/{before,fixed}/screen-parity.json`。`check_original_stage_links.py`仍通过46组实际原程序组合及车窗程序链接，保留0.1.1的显式阶段输出修复。

这证明修复了一个可独立复现的公共升采样错误；反馈方截图中当时的配置和完整NVIDIA游戏结果尚未实测。瞄准扩展的CameraView参数不匹配等日志另行保留，没有据此添加动作补丁。渲染支持需要更新到0.1.2并完全重启；人物包、菜单0.3.4、胸部物理及已取消的预览光影需求都不变。
