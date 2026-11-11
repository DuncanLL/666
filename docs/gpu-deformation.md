# GPU 人物变形候选

原版世界与 3D 人物预览共用同一个 GPU 变形快照。模组自动使用 GPU 变形。绘制上下文需要支持 OpenGL 4.3 和至少 8 个计算着色器存储块；能力不足或初始化失败时使用 CPU。BDEF 模型的静态顶点、骨权重和稀疏顶点 Morph 在显卡上保留。包含 UV Morph、SDEF 或 QDEF 的模型走 CPU。

`UpdateModelPose` 推进一次原有动作、骨骼 Morph、材质 Morph 和刚体物理。胸部 Jolt 随完成的姿态求解。GPU 根据有效 Morph 权重、最终骨矩阵和局部 Jolt 数据生成位置和法线。原版世界与预览直接绑定 GPU 输出。需要 CPU 网格时，`SkinCompletedPose` 可以把同一姿态只生成一次，无需再次推进动作或物理。

Viewpoint 的世界绘制目前走 CPU 兼容路径。其人物材质仍从 CPU 绘制记录读取上一帧的整张网格。后续接入 GPU 时，应让材质和运动向量直接读取 GPU 历史位置，并用 GPU 把共享顶点整理成 Viewpoint 的各材质段。Native 和 Eclipse 资源继续由原合包提供。

RX 9070 XT 的受控检查覆盖了没有 Viewpoint 时的原版人物、3D 预览、外套显隐、VMD 和 CPU 回退，以及在同一游戏帧切进 Viewpoint 时的姿态复用。索米和阿斯缇亚 JK 的 GPU 顶点和法线均与同姿态 CPU 回退对齐。

单人 VMD 序列的组件中位数。CPU 一列测原动作、物理和 CPU 蒙皮；GPU 路径一列测原动作、物理和 GPU 输入准备的 CPU 时间。两列均不含世界绘制和实际游戏帧率。

| 人物 | CPU 路径 | GPU 路径的 CPU 时间 |
| --- | ---: | ---: |
| 索米 | 0.85 ms | 0.59 ms |
| 阿斯缇亚 JK | 4.00 ms | 3.45 ms |
