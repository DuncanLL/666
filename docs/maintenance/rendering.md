# Viewpoint、Native、Somber 与 Eclipse 光影

当前框架将人物运行时、Native 和 Somber 交付在一个工坊核心包里。Eclipse 保留在独立维护构建。支持基线是 Steam 创意工坊正式 Viewpoint 0.1.7，具体 JAR 指纹由 [VerifiedViewpoint](../../render-support/java/gfl2/render/VerifiedViewpoint.java) 和 [发布来源记录](../../provenance/render-support.json) 核验。独立后端目录是构建和排查输入。进入游戏以后，玩家从 Viewpoint 选择实际光影。

## 从安装到当前帧

1. 合包的[启动入口](../../render-support/combined/java/gfl2/render/UnifiedStartup.java) 等待 Viewpoint，核对只有一份渲染接入和指定的官方宿主 JAR。经 ZombieBuddy 授权后，[安装器](../../render-support/combined/java/gfl2/render/Main.java) 展开正式包内的 Native、Somber，再挂接宿主。即使显示版本相同，重新打包的 Viewpoint JAR 也会触发指纹检查。
2. [ManagedShaderPack](../../render-support/java/gfl2/render/ManagedShaderPack.java) 用 `viewpoint-shaderpacks` 存放包，写入 `gfl2-rendering` 的管理状态，根据已有包的身份与指纹处理更新、备份或保留。对前代受管包有选择迁移逻辑。查“更新后仍用旧包”时同时读实际选项、管理指纹和实际绘制的 pack。
3. 三套光影共用人物会话提供的资料。普通 PZ 或别的 Viewpoint 包仍沿其自身绘制通道。启用了定义少前2渲染协议的包时，角色的专属着色接管相应图元。程序的判断条件是当前绘制的包和实际角色材质身份。
4. 捕获层提供位置、法线、材料显隐、各源材质的图元和上一帧资料。Viewpoint 的表现层在同一帧按宿主 phase 采样骨骼关联数据和顶点。这里应以 [PmxPresentation](../../game/java/gfl2/runtime/PmxPresentation.java) 和 [Bridge](../../game/java/gfl2/runtime/Bridge.java) 为准，别把绘制次数当作物理已经推进的次数。

[build_combined_render_support](../../tools/build_combined_render_support.py) 默认合并 Native 和 Somber。仅在维护 Eclipse 时先构建 Eclipse 支持，再传入 `--with-eclipse`。同一个宿主类同时有 Native 和 Iris 关注点时，合包钩子按 [RuntimeHooks](../../render-support/java/gfl2/render/RuntimeHooks.java) 中的顺序处理。适配时查实际命中的方法和计数，再看相机与渲染效果。

## Somber 的分发与回归

Somber 使用 Native 人物后端。角色包提供一份 PMX 和一份原材质目录，Native 和 Somber 读取同一份资料。新增角色时按现有流程更新人物目录，并将角色的完整制作端材质数据传给保护构建的 `--catalog-workshop`。原游 GLSL 和贴图继续使用外部资源目录。

[Somber 构建器](../../tools/build_somber_pack.py) 从本次构建的 Native ZIP 派生 `somber.zip`。官方 Somber 自带的天空、光照、预设和选项通过 `base=somber` 继承。构建器从已验证的 VP JAR 读取 Somber 的 `post.frag`，在其原始雾、透明层和曝光流程中接入人物显示转换。升级 VP 时，源码接点的匹配失败会报告具体语句。复核新后处理的顺序与输入后再更新构建器。保护构建从同批已封装的 Native 资料重新派生 Somber，沿用同一份公共人物程序。

[人物覆盖范围](../../render-support/java/viewpoint/render/Gfl2SomberCoverage.java) 读取当前帧少前2实际绘制的法线目标。普通 VP 模型也使用材质编号 31，必须同时核对少前2输出标记。每次后处理结束时恢复纹理单元与采样器。Somber 反射探针使用本包的天空入口与六向房间采样。主光方向、颜色和强度来自 VP 当前帧。

框架首次启动会将 `META-INF/gfl2/rendering/somber.zip` 展开到 `viewpoint-shaderpacks/Somber-GFL2`，在 VP 图形设置中显示为 **Somber GFL 2**。它和 Native 使用相同的逐文件指纹、受管更新和用户编辑保护。用 `tools/check_gfl2_unified.py` 检查空目录、延迟加载 VP、旧版升级及重复启动。实绘回归覆盖质量与性能预设、实时设置、来回切包、普通模型与少前2角色同屏、白天及室内夜景。记录设备、实际 JAR 和光影 SHA。

## 两个光影目录都没有生成

0.7.31 有两位工坊用户反馈缺包，其中一位说明菜单和目录均为空，另一位重新订阅后仍然缺包。另一次玩家实机日志记录了两包安装成功并被 VP 列出。两位反馈者的当次日志尚未取得，发生比例和各自原因待查。

| 在问题当次的 `console.txt` 里找 | 如何判断 |
|---|---|
| `[GFL2 Native] INSTALLED`、`[GFL2 Somber] CURRENT` 等安装记录 | 跟随每行末尾的实际缓存路径检查两个子目录及各自的 `pack.properties`。自定义 `-cachedir` 会改变位置。两个描述符都在而菜单缺项时，再查 VP 的 `shader packs:` 和 `left out`。 |
| `[ZB] Excluded` 与 `modId=GFL2Exilium` | 对照同一轮的 Java 模组加载列表、框架与 VP 路径、授权结果。ZombieBuddy 按 JAR 指纹保存决定，重新订阅同一份 JAR 后仍可沿用先前的拒绝。 |
| `[GFL2] Bundled light startup` | 读异常全文。官方 VP 指纹不符会在创建两个目录前退出。文件创建异常则按日志中的具体目标路径核对。 |
| `USER_FILE_PRESERVED` | 安装器已找到同名对象并保留了它。核对该路径是否为空、是否缺描述符。需要恢复时先把用户目录移到光影目录之外备份，再重启。 |

本版使用正式 ZombieBuddy 2.3.4 的 Java 加载入口、Steam 订阅副本和独立用户目录进行组件复核。两种加载顺序、同一会话后启用框架或 VP、中文及空格库路径均生成两包。已保存的拒绝、未启用 VP、宿主归档指纹不符和状态路径占用均可导致两个子目录都未创建。取得新日志时先对应到具体阶段，再决定修哪处。

## 一套人物怎样进入三套光影

少前2原程序、属性、贴图、GLSL、材质覆盖数据不随 Git 分发。按照核心及已启用分包里的 `media/gfl2_rendering`，由 [Gfl2PackData](../../render-support/java/viewpoint/render/Gfl2PackData.java) 按身份读回。[PmxShading](../../game/java/gfl2/motion/PmxShading.java) 以 `PMX-MAT1` sidecar 核 PMX 指纹、属性流和贴图映射。[PmxOriginalInputs](../../game/java/gfl2/motion/PmxOriginalInputs.java) 进一步核源码图元与实际 PMX 顶点、索引及材质编号。

- PMX 身份采用真实模型 SHA。一个角色同名衣装的顶点或材质槽变了，原 sidecar 和 renderer 对应就要跟着重核。
- 分包合并会校验各自连续的材质编号，并在主表中重映射编号。重名且内容不一致的源材质报冲突。还要校验各模型片段的 source renderer、源材质和属性索引。
- 纹理和采样器是两类输入。兼容异常首先核对原程序的 slot、sampler、路径、编码、色域、mip、alpha、ST 和属性流。不能根据中文角色名、PNG 同名或 PMX 显隐值推断所有原游效果。采样器出处和 UBO、SSBO 的大小要与原程序使用的布局对应。
- 特殊透明层、眼睛加法和乘法层保留实际源 pass 的含义。通过 [PmxDrawMesh](../../game/java/gfl2/motion/PmxDrawMesh.java) 的着色提交入口观察最终 material 和 flags。颜色与阴影的可见性可以不同，只有两种提交都不需要该部件时才能跳过相关帧上传。

## Native 的绘制路径

| 阶段 | 实际入口和职责 |
|---|---|
| 发现并准备 | [Gfl2NativeRuntime.prepareData](../../render-support/java/viewpoint/render/Gfl2NativeRuntime.java) 读取 `ShaderPacks.drawn()`。选择 Iris 或其他光影时没有本方案的 Native 数据。数据准备本身不调用 GL，渲染器在帧准备时按实际 pack 代数创建或释放。 |
| 固定帧所有权 | `beginNativeFrame` 确定本帧使用的渲染器和资料。[NativeHooks](../../render-support/java/gfl2/render/NativeHooks.java) 将属于少前2的源材质路由给适配层，避免主色和阴影分支重复提交。 |
| 深度与光照输入 | `beforeLighting` 触发适配器的深度/GBuffer 准备。角色继续共享当前世界的日夜、室内光、点光、携带手电、聚光与阴影，数据按宿主实际布局转换。 |
| 阴影与颜色 | [Gfl2NativeSourceRenderer](../../render-support/java/viewpoint/render/Gfl2NativeSourceRenderer.java) 处理合格的 ShadowCaster 和着色任务。颜色提交在预期的 opaque 后节点完成，并按实际源材质与通道绑定原程序、纹理和属性。需要记录透明度或发光问题时，分别看深度、原程序颜色与宿主后处理。 |
| 原游戏观感输入 | [Native manifest](../../render-support/native-pack/shaders/gf2/manifest.json) 记录原显示资料、环境通道和已确认的相机方向人物灯。该灯的值来自已有两帧捕获，其他场景的原游戏控制条件从证据继续区分。修暗处表现时先确认原画和宿主曝光、阴影、色域能对齐。 |

0.7.11 的 宿主兼容记录 专门区分游戏储存的场景点光、携带手电和聚光数据。它们在当时的宿主输入步幅分别为 20、11、24。进入原游输入适配器后，点光等可能已转换成新的记录结构，读字段时要核对在转换前还是转换后。天空反射还要确认宿主的云层与 sky-view sampler 单元。上游改变其中一处，容易出现夜间人物发黑、手电照射反常或反射不可信。[帧输入](../../render-support/java/viewpoint/render/Gfl2FrameInputs.java) · [Native 环境](../../render-support/java/viewpoint/render/Gfl2NativeEnvironment.java)

## Eclipse 的绘制路径

Eclipse 通过 Iris 接入。构建使用固定指纹的 Eclipse 基线、实际 Viewpoint JAR 和 [Eclipse 覆盖代码](../../render-support/eclipse)。[build_eclipse_support](../../tools/build_eclipse_support.py) 按定点来源替换受控实现和光影阶段。更新时优先核对构建清单和实际编译后的 pack。

| 阶段 | 实际入口和职责 |
|---|---|
| 链接和绑定 | [Gfl2Services.link](../../render-support/eclipse/java/viewpoint/render/Gfl2Services.java) 在配置已识别的 Iris pack 时创建源材质 bundle，增加公共宏与采样器绑定。帧状态属于对应的 `IrisTargets`，材质／程序属于对应的 `IrisPrograms`。 |
| 帧准备 | Iris 帧开始收集少前2角色资料，初始化当前光影阶段需要的目标、人物运动资料和环境输入。[Eclipse RuntimeHooks](../../render-support/eclipse/java/gfl2/render/RuntimeHooks.java) 保持与官方 `IrisFrame` 真实阶段对应。 |
| 不透明与透明 | [Gfl2Runtime](../../render-support/eclipse/java/viewpoint/render/Gfl2Runtime.java) 依据源材质 queue、pass 与覆盖状态处理角色。半透明衣料的实面覆盖在场景透明阶段之前写入。源阴影、描边、不透明前置通道和透明附着的时序要一起看。遮挡错误时应检查透明对象前的覆盖与深度。 |
| 人物灯与反射 | 原游人物光照适配通过 Eclipse 的有效相机、环境和光照数据构造。人物主灯可调节的代码在 `sourceKeyLight`、`sourceShadowLight`。光照输出保持按原写权限使用，原材质粗糙度与 Eclipse 反射 atlas 的线性粗糙度相匹配。 |
| 检查和资源退役 | 更换 Iris programs/pipeline 时分开释放材质与帧目标。检查缩放、TAA 运动向量选项、透明、阴影、雾和远近地形衔接。环境阶段的变化和人物着色变化分别记录。 |

已做过的 Eclipse 修正包括着色缓冲的 write-only 主灯约束、宿主预留 relief sampler 单元、按实际 Iris 宏展开的地形衔接、不同设置下的 TAA 运动与原游原程序输入。验收看真实宏、include 展开和 OpenGL 编译链接。[Eclipse 来源记录](../../provenance/eclipse-outline.json) · [启动检查](../../tools/java/RenderSupportStartupCheck.java)

## 切包和资源退役

人物随包持有的模型和渲染器的生命周期不同。切包先停用旧后端，当前帧已经拥有的资料照其队列退役。Native 运行时解绑 prepared/drawn 的数据并关闭旧渲染器，Eclipse 释放 `IrisPrograms` 和 `IrisTargets` 所在的 bundle。菜单改变 pack 后，要验证旧资源是否释放、新 renderer 是否重建、人物路由是否在适当阶段重新建立。

0.7.11 的旧问题出现在频繁切换人物后。Java 人物网格结束使用时，Viewpoint 独立网格池里的副本曾持续占用。当前 [PmxDrawMesh](../../game/java/gfl2/motion/PmxDrawMesh.java) 在退役时也归还宿主网格池记录，使用确切宿主的退役入口。核查掉帧时分别记录当前帧模型、网格池已用与容量、Java 和原生资源、实际 GPU 绘制和是否有外部模组异常。网格池与换包记录

## 玩家日志定位过的兼容问题

这些记录用来区分相似的表现。看到光影「加载成功」后，还要核对实际人物绘制有没有通过。

| 当时的现象 | 日志定位和修复 | 记录 |
|---|---|---|
| RTX 4060 Laptop 上人物出现时着色器链接失败 | `C5121: multiple bindings to output semantic ATTR2`。公共顶点适配拼接宿主辅助源码和原人物程序后出现输出位置冲突。显式分配跨阶段输出位置，使桥接法线和片元输入一致。复核包含报错程序的 46 组组合。 | 顶点与片元接口 |
| 开 Eclipse 后人物相对场景变大 | 玩家更新后原先的 ATTR2 错误未再出现。后来在本机用 TAA 升采样复现人物与场景的投影范围不一致。人物深度和颜色阶段改为使用与场景相同的裁剪空间缩放，12 组比例和抖动对照通过。玩家当时的实际 TAA 设置未随日志记录。 | Eclipse 升采样 |
| RTX 4070 Laptop 的 Native 已显示 `linked`，进到人物才编译失败 | 首个 GLSL 错误定位到公共 `native_indirect.glsl` 的局部名 `packed`，后续多条报错由它引起。改名并把风险标识符纳入构建检查，使用宿主展开后的实际源码验证 150 项 OpenGL 编译和链接。 | Native 跨驱动兼容 |
| 公司电脑上两套人物光影接入都未完成 | ZombieBuddy 调用框架时 Viewpoint 尚未进入类路径，读取 `ModelPass` 出现 `ClassNotFoundException`。在宿主入口可用时重试。带旧几何阶段的缓存也做了受控回归，修正版在首帧前更新了它。玩家故障现场实际读取的缓存未随日志提供。 | 宿主加载顺序与缓存 |

后续记录收录了 RTX 4060、GTX 1060 等玩家的可用反馈。具体后端和驱动未逐一收齐，按当时反馈记录。主光只写缓冲、地形采样槽与透明前的覆盖修正，也可从同一份阶段记录追下去。0.3.23 兼容阶段记录

## 故障从哪一层开始查

- **原版人物消失或换装夭折**，从人物会话、实际目录注册和 PMX/贴图加载查起。
- **原版人物能画，目标光影没有少前2材质**，先查实际 pack、两种后端的进入条件和模型 sidecar 指纹。
- **只在某个材质、昼夜或手电下异常**，追原程序 pass、状态、颜色空间、实际场景光的记录布局与对应 sampler。带透明的头发、眼睛发光和近景描边要单独取证。
- **启动或换包后黑屏、编译报错**，读取第一条着色器、宿主版本或 GL 状态错误。用实际宿主展开源码层级定位报错，核对预处理、缓冲布局、纹理格式、阶段衔接、采样预算和同步屏障，再查硬件环境。
- **能出图但切换愈来愈卡**，分清 CPU 动作、次级物理、渲染资源上传、宿主网格池与实际 GPU 时间。只看一个角色的一段帧率不足以归因。

正式发布按 [人物光影跨显卡检查](../render-release-standard.md) 记录实际分发后端各自的证据。组件规格、驱动出图、实际游戏和用户复测的边界见 [维护与验证](verification.md)。
