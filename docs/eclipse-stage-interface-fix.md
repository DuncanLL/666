# 渲染支持0.1.1：NVIDIA原人物程序链接失败

用户日志 `console (2).txt` 记录实际设备为RTX4060 Laptop、OpenGL4.6 NVIDIA616.64。独立渲染MOD启动成功，光影包已安装并被VP发现；报错在出现人物后首次链接原材质程序，程序对为 `0625dcfb5b91/4b1d0100e82b`，错误 `C5121: multiple bindings to output semantic ATTR2`。这不属于ZIP安装或普通场景光影加载失败。

公共顶点适配把官方VP的模型辅助源码与原人物程序拼接：前者导出多个未标位置的 `vUv/vPos/vNormal/...`，后者为 `vs_TEXCOORD*` 明确指定0–10等位置，另有未标位置的桥接法线。NVIDIA实际链接出现重复输出语义。本机AMD曾能链接，因此此前本机绘制通过不能证明NVIDIA也通过。

修复集中在公共 `AimohGf2Rc2Vertex` 和其程序创建调用：官方辅助源码的跨阶段输出在这个原程序里只用于顶点内部计算，因此改为普通顶点全局临时量；原人物程序的显式输出位置保留。桥接世界法线分配在原输出之后，并同步到需要它的片元输入。所有顶点跨阶段输出现在都明确定位，没有按显卡、角色、动作或程序SHA特判。没有改原材质公式、实际贴图、原程序payload或额外添加绘制Pass。

接口定义参考：[Khronos ARB_separate_shader_objects](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_separate_shader_objects.txt)。不把“混用显式与隐式位置”笼统说成规范禁止；这里修的是已由NVIDIA实际报错暴露的接口分配依赖。

`python tools/check_original_stage_links.py` 从实际完整人物catalog生成去重组合，覆盖46组原VS/FS及早期Forward程序。检查全部输出明确定位且无重叠、桥接法线两端一致，并通过实际OpenGL驱动链接；包括老板报错的精确程序对。修前的结构检查会拒绝未定位的 `vUv`。本机实际GPU为AMD9070XT；NVIDIA真实复测仍待用户/反馈方确认，没有将本机通过标成跨显卡实测通过。

另一类故障单独保留：日志中的 `ProjectViewpointADS` 对 `CameraView` 挂接报参数类型不匹配，随后瞄准记录出现约2917万的瞄准距离、非法外部姿态输入，最后主线程停在 `pvads.ADS.wrap → lookEffects`。这些足以确认瞄准扩展存在独立异常，不能把它归为本次GLSL链接失败，也没有在本次修改动作/物理核心来掩盖。按用户优先级，本次不做该扩展适配。

渲染支持版本0.1.1、人物菜单版本0.3.3。两者分别需要更新对应工坊项目。保留人物ID3834454638和渲染支持ID3834707439，不改用户当前选择、不启动游戏、不自动上传。
