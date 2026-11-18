# 从原材质到能绘制的 PMX 三角形

从[人物导入](pmx-import-workflow.md)取得新的 PMX、profile 和稳定 ID。同一 PMX 的普通预览正常后，再做原材质接入。制作时要能回答一个具体问题：**屏幕上这块脸的一个三角形，取自哪个 PMX 槽、哪个原 renderer 和材质、哪个顶点／片元程序，最终由谁提供常量和采样器？**

## 先用交付包走通一张脸

沿用上一章的 PowerShell 变量。下面从核心登记追到莱娅原皮第一个 draw part，重算材质常量块，检查其原纹理和所选 pass 的 GLSL payload。给工具的是角色分片，打印的 `local_source_material` 也是分片内编号。

```powershell
& $py tools/character_workflow/trace_material.py --core-media $core --role-media $role --pack GFL2Laiya --model-id $id --part 0 --pass-name GFCharForward --write-buffer build/workflow-example/face.f32
if ($LASTEXITCODE -ne 0) { throw 'Material readback failed' }
```

| 莱娅原皮的输入或结果 | 现场可复核的值 |
|---|---|
| PMX 槽 → 绘制片段 → 原材质 | 槽 0 → `Face` → 分片材质 5，`c_Leva_slg_faceuber`，shader 为 `gf_shader/pbr/character/uber` |
| 局部几何 → 部件坐标系 | 1,453 个紧凑顶点、7,698 个索引 → `renderer_frames.materials["5"]` → `Head_M` → 这套 PMX 的头骨 |
| 原材质常量块 | 544 字节，重算 SHA-256 为 `77f04aed02e4bd955014facf41f7f067dfb9515071672318b63ad09e9d46fe9c`。例如 `_BaseMap_ST` 的 4 个分量从字节 16 开始。 |
| 一次纹理查询 | 片元变体 `a2f224c7…` 的 `_BaseMap` 使用纹理槽 2，实际资源由材质的 `texture_bindings._BaseMap.resource` 查询。`_RampMap` 在同一变体里有独立的内联 sampler 记录。 |

[追踪和常量块生成](../../tools/character_workflow/trace_material.py) · [运行时材质与资源读取](../../render-support/java/viewpoint/render/Gfl2PackData.java)

## 新衣装怎样准备源材质

1. **找到原 renderer。** 固定原资源版本和目标衣装 prefab，从每个实际使用的 SkinnedMeshRenderer 取 mesh 引用、根骨、submesh 序号和 `m_Materials` 中同序的材质引用。取对应的原始绑定网格。把可以在游戏里打开的衣物／配件也列进去。一个路径引用应记为“所在 CAB + PathID”；`m_FileID=0` 在当前文件解析，正值沿当前文件的 externals 表按 `m_FileID-1` 定位目标 CAB。纹理也按同样规则递归。名称只用于人读。
2. **记录每个材质的原始状态。** 从 Material 的 `m_Shader` 解析到对应 Shader，保留材质序列化的浮点／颜色或向量／纹理引用、tiling、offset、keywords、禁用 pass、render queue。Shader 属性的类型和默认值来自对应 Shader 的 parsed form。分别保存每个 pass 的 Cull、ZTest、ZWrite、Blend、颜色写掩码、Stencil 和受属性控制的状态。来源只有序列化值的字段要标记出来；运行时覆盖值需要抓帧或对应控制器证据。
3. **取纹理原 payload。** 连同尺寸、格式、颜色空间、mip 数、过滤和各轴 wrap 写入 `textures[资源键]` 的 `metadata` 和 `payload`。材质槽记录 `resource/scale/offset`。原法线、RMO、Ramp、透明遮罩和普通颜色图按各自程序的解码语义保留；不要把检查用 PNG 当 HDR、压缩纹理或整条 mip 链。源引用为空时记录原 Shader 对该槽的默认值及实际消费条件。
4. **按材质和 pass 选择程序。** 读取该 Shader 子着色器的 pass、顶点／片元 subprogram、对应平台段和 local/global keywords。项目处理过的 Unity 数据中，先按平台的 offsets、compressedLengths 和 decompressedLengths 解开程序段，再由 `m_BlobIndex` 取正确 subprogram 的代码与反射。对提取的完整 DXBC 容器计算 SHA。不同顶点阶段、片元阶段或关键字组合分别登记在 `programs`。同 SHA 的已支持变体复用 `glsl`、`consumed`、适用时的 `early_fragments`。遇到全新变体，须从它自己的指令和反射生成 OpenGL GLSL，保留寄存器、采样操作、分支和输出；记录转换器及版本，随后走双后端的实际阶段编译链接。仓库中的运行时与示例工具从已提取的程序和反射开始消费。原资源版本对应的容器读取器和新 DXBC 变体翻译器属于制作输入。
5. **生成常量块。** 对所有参与颜色、早期、描边及阴影的阶段取被读取的 `UnityPerMaterial` 字段并集。按反射的字节偏移、类型、维数和数组步长排布，逐字段写小端数据。已知浮点布局的写法见 `buffer_bytes`。优先取材质显式值，其次对应纹理的 ST 或 Shader 声明默认值。只有明确是颜色且原程序要求线性输入时转换 RGB，alpha 原样保留。方向向量即使存放在 `m_Colors` 也保持数值。字段宽度或类型未解析、同名字段出现不兼容布局时，回查变体再烘焙。
6. **把资源路由写完整。** `materials[i].id=i`，并提供名称、shader、buffer、bindings、texture_bindings、passes 和队列。对程序分别保存 `textures` 与 `samplers`。纹理描述的 `m_Index` 表示纹理绑定位置，`m_SamplerIndex` 可以复用另一个采样器。内联 sampler 的 `bindPoint` 和状态来自程序自身。`consumed` 对应同一程序的实际常量缓冲和寄存器读取。最终资源条目用 `storage: "mod"`、逻辑路径 `gf2/data/<完整内容SHA>.<扩展名>`、`bytes`、`sha256`；实体文件在核心或本角色的 `media/gfl2_rendering/data/`。原 DXBC 的 SHA 是程序身份，翻译后的 GLSL 文件有自己的内容 SHA。

一行制作台账至少应含 PMX 完整 SHA、PMX 材质槽和三角范围、原 CAB/PathID、renderer/submesh、原材质键、pass/stage/关键词、程序 SHA、纹理及采样器、常量布局和证据文件。明确区分缺失、原始默认及条件性关闭的输入。

项目早期的原程序转译采用 Unity HLSLcc。需要新增变体时，可从它的 `TranslateHLSLFromFile` 或 `TranslateHLSLFromMem` 接入。提供该阶段的 DXBC、目标桌面 GLSL、阶段依赖和反射回调；保留原指令及输入输出签名作对照。把实际读取的常量寄存器映射回 Unity 反射字段，保留纹理与 sampler 的对应，分别记录原程序和生成 GLSL 的 SHA。传给原程序的宿主位置、法线、颜色及上一帧属性仍需通过项目的顶点桥，着色器链接和实际像素另验。可优先用一个已经支持的变体校对工具链，再处理未知变体。[Unity HLSLcc](https://github.com/Unity-Technologies/HLSLcc) · [转译器接口](https://github.com/Unity-Technologies/HLSLcc/blob/master/include/hlslcc.h) · [公共顶点适配](../../render-support/java/viewpoint/render/Gfl2Rc2Vertex.java)

[原材质常量烘焙器](../../tools/character_workflow/bake_material.py) 可以直接消费导出的 Unity Material 类型树。输入 JSON 的 `material` 放含 `m_SavedProperties` 的原对象，`properties` 按属性名保存 Shader 的 `type/value`，`bytes` 取缓冲反射大小。将已经核对的多 pass 浮点字段并集放入 `layout`，如 `{"name":"_BaseMap_ST","byte_offset":16,"components":4,"type":0,"array_size":0}`。以实际反射为准填写。工具支持本制作链的浮点标量／向量，遇到新的字段类型或数组会指出需要扩展的位置。

```powershell
& $py tools/character_workflow/bake_material.py --input $materialInput --output build/workflow-example/source-buffer
```

产出的 `bindings.json` 含字段来源与 buffer 描述符，`data/` 含内容寻址的原始字节。用真实制作留档的 52 个材质输入复核过这一规则，重建缓冲摘要与对应烘焙记录全部一致。

## 网格不同，怎样把原属性落到 PMX

**先对齐绑定姿态，再在 UV 三角形里找角点。** 源 PMX 保有自己的顶点、骨号和蒙皮。原网格提供渲染所需的切线、顶点色和各套 UV。以独特的相同 UV／拓扑点配对，两边用同一坐标系比较绑定位置；重叠 UV 岛优先使用空间残差和部件归属消歧。从分布充分的对应点求源到 PMX 的仿射，记录坐标反射、单位、样本数及残差分布。验证独立点和脸、头发、衣服边界；示例角色的倍率、镜像轴和根骨不可当作别的资产的缺省值。

- PMX 材质的面索引数划分目标三角范围。用源 renderer/submesh、底图身份、候选 UV 覆盖和绑定位置确认每个三角来自哪个原材质。一个 PMX 槽可按原材质拆成多个 part。莱娅示例是 **22 个槽、23 个 part**。
- 在候选源三角形内算 UV 重心权重，插值得到源属性及对应的绑定位置。比较位置残差，保留角点接缝。法线按基变换的逆转置变换并归一，切线按变换后的法线正交化，镜像时核切线手性。新拓扑需要局部参考系时，用已匹配边拟合旋转并记录退化区域。重叠岛、镜像切线、UV 外的角点需逐块取证。制作里也用过绑定空间最近表面投影；应记录候选面、投影残差和允许的上限。
- 已确认材质、UV 与绑定坐标后，可用[严格角点转移工具](../../tools/character_workflow/transfer_corners.py)处理一个 part。源 NPZ 的 `triangles` 为 `T×3×18`，最后一维依次为已对齐的绑定位置 3、法线 3、切线 4、原 COLOR 4、UV0 2、UV1 2。目标 NPZ 提供 `positions[V,3]`、`uv0[V,2]`、`skin[V,8]`（骨号四个、权重四个）和该 part 的全局 PMX `indices[T,3]`。两边 UV0 的 V 方向必须统一；用原 PMX 读取器与本项目的原网格导出配对时，目标通常需 `(u,1-v)`。目标索引继续引用整套 PMX 的顶点编号。以下限值单位是**对齐后的 PMX 单位**，从实测残差确定。

```powershell
& $py tools/character_workflow/transfer_corners.py --source $alignedRendererNPZ --target $targetPartNPZ --max-distance $verifiedTolerance --output "$matched/part-0.npy"
```

对同 UV 且同绑定位置、属性仍有歧义的角点，工具报出目标三角、角点和顶点号。把候选收窄到有证据的源岛／源面后重跑。只需对有差异的部分重做映射。

## 写成运行时的几何与材质分片

每条角点记录有 **24 个小端 float32**，占六个 RGBA32F texel。

| 索引 | 内容 |
|---|---|
| 0–3 | 法线 xyz、原 PMX 顶点索引加一，零是无效标识 |
| 4–7 | 切线 xyz 与手性符号 |
| 8–15 | 原 COLOR.rgba、原 UV0.xy、原 UV1.xy |
| 16–23 | 四个 PMX 骨号和四个对应权重 |

为每个 part 准备 `part-N.npy`，形状为 `三角数×3×24`。同目录的 `draws.json` 提供完整 `pmx_sha256`、`original_pmx_vertices`、`original_pmx_materials`、属性纹理宽度 `texture_width`、已测得的 `source_affine`（4×3）、`renderer_frames` 和依序的 `parts`。每个 part 显式写 `name/pmx_material/source_material/source_identity_resolved`。根绑定保存源 renderer 的组名、目标骨号和 `bind_rotation`，材质 ID 字符串映射到组；脸和身体可能分别跟头、颈或胸。根、仿射或原材质的来源不明时，先解决对应关系。

[bake 工具](../../tools/character_workflow/source_geometry.py)按完整 96 字节属性记录去重，每个 part 再按 `(PMX 顶点号, 属性记录号)` 去重。几何块先写成对的两个 uint32，再写相对本 part 的三角 uint32 索引。描述符的 `vertex_offset/index_offset` 是**字节**，`vertices/indices` 是**数量**。按生成的 `model.json` 追加 `characters.models[]`，`data/` 的两个 blob 放进该角色的资源目录。

下面的往返例子也会生成一份可直接检查的 `draws.json` 和每个 part 的输入样本。它检验二进制封装；新角色的原资产对应还须按上一节建立。

```powershell
$sha = (Get-FileHash $source).Hash.ToLowerInvariant()
$matched = 'build/workflow-example/matched'
$baked = 'build/workflow-example/baked'
& $py tools/character_workflow/source_geometry.py unpack --fragment "$role/gfl2_rendering/packs/GFL2Laiya.json" --sha $sha --data "$role/gfl2_rendering/data" --data "$core/gfl2_rendering/data" --output $matched
if ($LASTEXITCODE -ne 0) { throw 'Corner readback failed' }
& $py tools/character_workflow/source_geometry.py bake --matched $matched --pmx "$out/model.pmx" --profile "$out/profile.json" --output $baked
if ($LASTEXITCODE -ne 0) { throw 'Geometry bake failed' }
```

莱娅原皮的 69,626 条属性记录装入 1024×408 的浮点纹理。重建的属性块 SHA 为 `6b891edbf78f730eff82774ae9ec1e71a7b0041e85626ee0ee2f3cd9ce901311`，几何块 SHA 为 `c753c879491d0e942595e193703fd0cc711f487fb2ef0a0d80b98be1cdff7d43`。两项都可与现装文件逐字节比较。[Java 解码器](../../game/java/gfl2/motion/PmxOriginalInputs.java)

**模型侧还要交付一份 PMX-MAT1 旁文件。** 在模型旁写 `shading-danrp.json`，使用 format 1、协议 `PMX-MAT1`、真实 PMX SHA、顶点数和头骨索引。以实际头骨和脸部顶点求 head_origin、正交的 forward/up 及正的三轴半径，material_ids/kinds 的长度对齐 PMX 材质槽。已烘焙属性的额外 UV 可按原 PMX 顶点取代表值另存 `uv1-danrp.f32`，每顶点两个小端 float32，记录文件 SHA；角点接缝继续由完整属性纹理表达。读取器核对有限值和绝对值小于 8。原程序路径设置 `danrp_payload=1`，顶点纹理容量按模型顶点和每点所需 texel 配齐。兼容文件名留在现行协议中。[旁文件读取与选择](../../game/java/gfl2/motion/PmxShading.java)

对于新的标准 GF2 衣装，可用[旁文件生成器](../../tools/character_workflow/sidecar.py)生成上述两个文件。先从源模型确认脸部槽及头部坐标轴。下面的槽和方向是本例的输入：

```powershell
& $py tools/character_workflow/sidecar.py --pmx "$out/model.pmx" --profile "$out/profile.json" --baked $baked --face-slot 0 --forward 0 0 1 --up 0 1 0 --output build/workflow-example/sidecar
```

将结果与旧旁文件分别保存。莱娅现装基准使用全零的顶点级 UV1，并采用较大的头部包围范围；按脸部槽取范围、按角点取代表 UV1 会生成另一份候选。上文逐字节相同的结果针对完整角点属性块、几何块和脸部材质缓冲。重新生成头部范围或顶点级 UV 后需在两后端复查脸、眼、遮挡与描边。

## Native 和 Eclipse 各在哪里适配

公共渲染目录的八个字段为 `materials, textures, shader_property_defaults, programs, glsl, early_fragments, consumed, models`。核心目录和每个启用分包各持一份相同字段集。合并时将本包的材质 ID 转为有效目录中的 ID，同步更新 `parts[].source_material` 和 `renderer_frames.materials` 的键。format 6 光影 manifest 指向核心目录；运行时读取 bootstrap、核心目录原字节和成功纳入的分片字节计算数据身份。模型匹配从完整 SHA 的前 12 位十六进制数拆成两个 24 位 token，再核拓扑；token 碰撞要显式失败。角色资料写一次，两个后端使用同一来源。[目录与合并](../../render-support/java/viewpoint/render/Gfl2PackData.java) · [按后端分发的公共接口](../../render-support/combined/java/viewpoint/render/Gfl2SourceHost.java)

| 绘制阶段 | Native | Eclipse / Iris |
|---|---|---|
| 帧入口 | 识别当前绘制的受管 Native 包，准备帧、冻结模型与材质资源。 | 在 Iris pack 链接和帧准备节点绑定源材质及当前 targets/programs。 |
| 空间与光照 | 从真实宿主帧读投影、主光、阴影、环境与点光，建立每个 renderer 根的变换，再填原着色程序的输入。现行 `original_camera_fill` 按已记录的 Native 契约保留。 | 用 Iris 当前相机、TAA 抖动、渲染尺度和光影的光照／反射构造同类输入。早期深度和颜色输出共用正确的裁剪空间尺度。 |
| 排序、透明和释放 | 在宿主相应的深度、阴影和颜色节点提交原材质 pass。按实际批次与资源就绪状态判断，帧代切换释放旧 renderer。 | 依照 queue、覆盖、透明和阴影约束顺序提交，分别跟随 IrisPrograms/IrisTargets 生命周期释放。 |
| 从哪读实现 | [Native 入口](../../render-support/java/viewpoint/render/Gfl2NativeRuntime.java) · [原程序提交](../../render-support/java/viewpoint/render/Gfl2NativeSourceRenderer.java) | [Iris 链接](../../render-support/eclipse/java/viewpoint/render/Gfl2Services.java) · [Iris 绘制](../../render-support/eclipse/java/viewpoint/render/Gfl2Runtime.java) |

新衣装命中已支持变体时，通常只需追加数据。换 VP 或 Eclipse 基线时，重核官方宿主 SHA、方法签名、阶段次序、宏和采样槽。先验证源输入，再看纹理、光照、曝光和最终画面。[构建、验证与回退入口](workflow-build-checks.md)
