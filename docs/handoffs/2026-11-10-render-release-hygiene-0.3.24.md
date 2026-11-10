# R116 光影 0.3.24 发行包清理

范围是 PZ 42.22、官方 Viewpoint 0.1.7-rc4 的光影合包。Eclipse 单独构建版本为 0.1.29。普通包和可选封装包均从同一组运行源码生成。

## 盲读结果和清理依据

- 第一次符号处理试读仍能直接看到光照公式和调校值。V2 封装增加了追踪加载器和执行解包的步骤。外部试读找到了对应片段编号并报告取出明文，截图没有提供文件校验值。客户端随包提供还原所需数据，封装定位为减少直接拆读的线索。
- V2 中的 99fd…comp 是现役 Eclipse 人物环境探针。它经清单顶层的 native_probe 资源记录间接传给 AimohGf2Runtime 的 configureLocalProbe。只查明文路径或 kernels 会漏掉这条引用。此前将它判断为旧副本有误。
- 普通包保留整份探针源码。封装包只封装已核对的 2,936 字节自写探针逻辑，前 11,183 字节保持逐字节一致。读取端沿用 V2 的加载入口，完整探针恢复到清单记录的 SHA-256。
- 两种构建都从两套发行清单移出补光的抓帧说明、证据指纹和事件号，同时移出 Native 的旧阶段说明和 Eclipse 的环境单位、坐标调试说明。实际补光方向、颜色、坐标换算、功能标记、探针及其他着色程序引用保留。
- 两种构建都剔除了无引用的 AimohGF2Services$Snapshot 历史类。其他普通包 Java 类与 0.3.23 逐字节相同。原 VP 文件、签名和第三方署名按既有构建边界保存。

## 维护和存档

- Native 的带记录原稿在 render-support/native-pack/shaders/gf2/manifest.json。Eclipse 的带记录原稿和历史类都能从 provenance/eclipse-outline.json 记录指纹的冻结参考 JAR 读回。两套原稿都经过同一个发行字段清理函数。
- Java 内嵌效果已按引用盘点。AimohGf2OutlineBridge 管描边宽度和手性补偿，AimohGf2SurfaceControls 管法线与粗糙度调节，Eclipse 的 AimohGf2Runtime 管环境输入修正和场景深度重投影。这些是现役实现，沿用原有构建和混淆。部分 GLSL 字符串仍可通过 Java 类读取。
- 日常编辑使用可读源码。封装片段范围由 release-protection.json 的哈希与源码锚点固定。构建输入和映射留在本地 build 下的 private/，发行目录只保留正常工坊结构。升级 Eclipse 参考包时需重新核对混合代码的归属和清单里的间接资源引用。
- 新包位于 build/render-release-hygiene-0.3.24/Protected-final/Workshop/R116RenderSupport。该次普通包在同级 Plain 下。旧 V2 和普通 0.3.23 对照已分别保留。拆包审计与 GPU 对照记录分别为 archive-audit.json 和 component-validation.json。

## 验证

- 两种构建均通过 ZombieBuddy 正常入口、官方 Viewpoint 两套包发现、选项与原生 Workshop 校验。封装包在玩家目录保留探针占位符，内存读取后验证完整探针和现役环境光计算阶段的原 SHA-256。递归拆包核对了六个已知明文指纹。
- AMD Radeon RX 9070 XT，驱动 26.9.1.260806。Native 36 帧换人物及 48 组人物着色程序链接通过。Eclipse 开 TAA 绘制 8 帧，探针启用，颜色和深度输出与 V2 一致，两套 GL 错误均为零。
- 四份 Native 计算阶段、66 份 Eclipse 宿主展开阶段与 V2 字节相同，对应 33 组已通过的 OpenGL 参考编译链接输入。发行清理发生在构建时，新增探针封装沿用原有的加载时还原路径。
- 状态 LIMITED_HARDWARE_VALIDATION。真实存档、Steam 订阅冷启动、NVIDIA 与 Intel 仍待单独验证。该轮为独立构建和 Git 维护。
