# 从一个 PMX 做到游戏中可选的人物

**适用环境为 Windows x64、Project Zomboid B42.22、官方 Project Viewpoint 0.1.7 和 GFL2Exilium。** 第一次接手可以拿已有衣装做一趟对照。先从 PMX 重新生成标定并用游戏核心读回，再从同一衣装的渲染分片反查一张脸的原材质，最后重建几何数据并比较 SHA。这样能分别发现骨架、材质归属和封装问题。新增衣装时沿用同样的读回步骤。[材质和光影制作](source-material-workflow.md) · [运行时与玩家日志](rendering.md)

## 先准备输入

| 输入 | 用途与来源 |
|---|---|
| 自己有权使用的一套 PMX、相对路径贴图、可选的同名 `.controls.json` | PMX 是这套衣装的唯一源网格、骨架、权重、Morph 与 Bullet 定义。保留一份只读原件。 |
| 同一 B42 源骨架的骨名顺序、参考姿态和弯臂姿态 | 制作时从游戏 `media/models_X/Skinned/MaleBody.x` 的骨架和 Bob 动作取得。参考为 `Bob_TPose` 的起始帧，握持标定用 `Bob_IdleAimBat` 时长的 25%。可从同一骨架的一份已验证 `profile.json` 读取这三项作为输入。 |
| 当前框架人物 JAR、官方宿主、ZombieBuddy、游戏自带 JRE | 候选工具直接运行框架中的 `WorldPoseRetargeter` 和原生库。框架 JAR 可以取自已经取得的核心包 `42/media/java/client/GFL2Exilium.jar`。 |
| 同一衣装、同一资源版本的原材质、原网格和着色程序 | 原程序着色阶段另需原始 renderer、submesh、绑定姿态、材质与 Shader 对象、纹理 payload 及程序反射。已有角色包可用于逆向核对制作结果。 |
| 重建两套后端所需的本机文件 | [路径模板](../../config/paths.example.json)、[资源指纹表](../../resources/manifest.json)、[Native 来源](../../provenance/render-support.json)、[Eclipse 基线](../../provenance/eclipse-outline.json)。其中保留的 Eclipse 参考 JAR、显示资源和原游数据需另行提供。 |

Git 中携带制作方法、工具、注册数据和运行时源码。外置输入按上述来源和指纹准备。仅有 PMX 与普通颜色图时，可以完成下面的人物导入；继续制作专属材质时还要取得对应原材质资料。

## 在新检出中运行一次

在仓库根打开 PowerShell。下面使用 Python 3.12。导入器使用 MMD Tools 的独立 PMX 读取模块；固定到测试过的上游提交，并在运行前校验原始字节。[上游读取器](https://github.com/MMD-Blender/blender_mmd_tools/blob/3451990faea2e0def110e7f6027f5c8575451e8b/mmd_tools/core/pmx/__init__.py)

```powershell
New-Item -ItemType Directory -Force .local/pmx-reader | Out-Null
py -3.12 -m venv .local/workflow-venv
$py = (Resolve-Path .local/workflow-venv/Scripts/python.exe).Path
& $py -m pip install numpy==2.2.6 JPype1==1.6.0
& $py tools/character_workflow/test_workflow.py
if ($LASTEXITCODE -ne 0) { throw 'Attribute transfer or material layout example failed' }
$reader = '.local/pmx-reader/pmx_reader.py'
Invoke-WebRequest 'https://raw.githubusercontent.com/MMD-Blender/blender_mmd_tools/3451990faea2e0def110e7f6027f5c8575451e8b/mmd_tools/core/pmx/__init__.py' -OutFile $reader
if ((Get-FileHash $reader).Hash -ne 'ec716cbbe235a269268efef32594f9c095d2f21264c91b81507acd82e99b2a29') { throw 'PMX reader hash mismatch' }
```

在 `.local/paths.json` 填写本机**绝对路径**。`game_root` 指向含 `projectzomboid.jar` 和 `jre64` 的目录；`viewpoint_jar`、`zombiebuddy_jar`、`reference_jar` 分别指向两项依赖和当前框架 JAR；`pmx_reader_scripts` 指向刚才的读取器目录。使用上面的虚拟环境时，`python_dependencies` 可填空字符串。只运行这组人物制作工具用到这些键，重新编译和封装还要补齐模板的其余键。

以下示例从**已持有的核心和莱娅角色包**读输入。把两个示例路径换成各自的 `42/media` 目录。PZ 源姿态直接使用[仓库已登记的参考 profile](../../game/packs/GFL2Asteria/42/media/gfl2_pmx/models/pmx-412bd037301ebe18/profile.json)。使用新的候选目录，避开已安装 MOD 和作者上传目录。

```powershell
$core = 'D:/your-mods/GFL2Exilium/42/media'
$role = 'D:/your-mods/GFL2Laiya/42/media'
$id = 'pmx-abe3eaaaa9d04264'
$source = "$role/gfl2_pmx/models/$id/model.pmx"
$poses = 'game/packs/GFL2Asteria/42/media/gfl2_pmx/models/pmx-412bd037301ebe18/profile.json'
$out = "build/workflow-example/models/$id"
& $py tools/character_workflow/import_pmx.py --pmx $source --source-poses $poses --output $out
if ($LASTEXITCODE -ne 0) { throw 'PMX import failed' }
```

[导入入口](../../tools/character_workflow/import_pmx.py) 取源 profile 中的 `source_bone_names`、`source_reference`、`source_bend`。目标人物的骨号、父索引、比例、手掌、物理和外观均从本次 PMX 求得。它复制模型与贴图，校验换装定义，生成 `profile.json`，调用实际 `PmxModel.load`，依次推进参考姿态和弯臂姿态，检查所有输出顶点有限。成功才把临时模型目录改成指定输出名。输出记录的 `scope` 对应这两帧组件验证。已有 `chest.bin`、个性化握持设置、语音和渲染分片属于后续资料，重新标定一套已发布衣装时应对照原文件增量合并。

**莱娅原皮的复核值**为 55,843 个顶点、471 根骨、22 个 PMX 材质槽，完整 SHA-256 为 `abe3eaaaa9d042640f08e4f53f7d55a05d1e3c377798854b1f4c3ffee81a099f`。以另一角色包提供 PZ 源姿态重建后，源／目标骨索引、参考姿态、目标中立姿态和弯臂标定轴与已交付资料一致，比例约 20.2091。这些数值用于核对这个例子；新模型使用自己的测量结果。

## 骨架与物理是怎样接起来的

1. 原生加载 PMX 后，逐项核对解析器与原生核心的骨名和次序。用 PZ 的 `Bip01`、骨盆、脊柱、颈、头、两侧锁骨至腕和大腿至踝，与 MMD 的语义骨及别名建立对应。缺必需骨或多个驱动角色落到同一目标骨时停止，核对骨骼语义。可选指根以真实存在的骨为准。[标定实现](../../tools/character_workflow/calibration.py)
2. 取目标初始骨位置和父链。以每根受控骨的局部平移、四元数、缩放共 10 个值构建中立姿态。源 PMX 有适用的 T-Pose 骨 Morph 时先应用，再用 PZ 参考段方向逐段对齐臂和腿。每次修改后重新读蒙皮矩阵，把所求世界旋转转回父空间；对齐腿后恢复原脚朝向。当前实现用 Y-up、地面原点下的目标颈高 / 源参考颈高求 `unit_scale`。矩阵转置和 Z 反射的位置以实现为准。
3. 用左右腕和对应指根建立掌面，弯臂参考姿态求肘轴。切角色需要自己的掌面资料；`palm_ready=false` 要单列。公共握持在已解算的人体姿态上求腕和持物偏移。常规空手项目采用 `hand_relax_strength=0.7`。[世界姿态重定向](../../game/java/gfl2/motion/WorldPoseRetargeter.java) · [游戏会话](../../game/java/gfl2/runtime/ModelSession.java)
4. 对作者刚体及关节清点类型、关联骨和受驱动父链冲突。输出 `physics.enabled` 和 `primary_bone_conflicts`。源数据缺作者物理时仍可导入普通网格；报告其实际能力。实时角色在最终骨架姿态完成后推进物理；预览或阴影重复绘制读取现有结果。胸部软体另按真实权重、刚体和关节生成专属区域资料。[物理区域检查](../../tools/chest_model_data.py) · [运行时载入](../../game/java/gfl2/motion/PmxModel.java)
5. 普通材质从 PMX 的材质号、底图、颜色、Alpha、显隐、双面和 Morph 进入 `ModelAppearance`。同名 `.controls.json` 的 `pmx_sha256` 必须对应源文件。组、默认选项和规则引用本衣装实际材质号／Morph 号，切帽子或头发时原位应用。检查展开选项、切换再切回、两侧手及皮肤显隐。[外观实现](../../game/java/gfl2/motion/ModelAppearance.java)

## 一个稳定模型 ID，三份登记

| 消费方 | 核心中的入口 | 分包中的对应文件 | 验收 |
|---|---|---|---|
| 人物核心 | `42/media/gfl2_pmx/registry.json` 的 `packs` 及可选 `pack_mod_ids` | `media/gfl2_pmx/packs/<pack>.json`，模型条目的 `model/profile` 相对于该包的 `media/gfl2_pmx` | 启用能读回实际模型，停用能回到未加载状态。 |
| 整备室 | `42/media/ui/GFL2ReadyRoom/catalog.json` | `media/ui/GFL2ReadyRoom/packs/<pack>.json` 的模型、标签及配图 | 按同一 ID 找到角色、衣装、原皮标记和头像。核对 `roomStarterSkins` 与锁定状态。 |
| 着色 | `42/media/gfl2_rendering/characters.json` 和光影引导 manifest | `media/gfl2_rendering/packs/<pack>.json`、`media/gfl2_rendering/data` 及模型旁的 `shading-danrp.json` | 用完整 PMX SHA、顶点与材质数定位实际绘制资料。 |

[框架登记](../../game/mod/42/media/gfl2_pmx/registry.json) · [莱娅分包登记](../../game/packs/GFL2Laiya/42/media/gfl2_pmx/packs/GFL2Laiya.json) · [整备室与存档契约](character-systems.md)

首次入库可用 `pmx-` 加源 SHA 前 16 个十六进制字符建立 ID。已发布衣装优先沿用注册和存档里的 ID。重新导出的字节变了，就重新计算完整 SHA，重新标定并重做材质和顶点映射。候选目录中保存新增文件以及变更前后身份清单；合包时追加新登记，已有角色继续按原键加载。

制作分包时，将版本根放在 `Workshop/<包>/Contents/mods/<MOD ID>/42/`。以仓库 `game/packs/<包>/42` 的已有结构为准增量放入 mod.info、相对路径登记和整备室条目。源侧受管 JSON 与真正的二进制资源各归对应位置。对照 `resources/manifest.json` 中核心的 `files` 和角色的 `character_packs[包].files` 登记外置文件的字节数和 SHA。打包前核对哪些描述符已随对应版本纳入源码，避免同一路径被旧外置副本回盖。独立角色包加入框架的角色列表后，分别测试只启用核心、只加目标包、再加一个旧包。

进入下一步前保存 PMX 指纹、骨数、顶点数、材质数、比例、缺失骨／贴图、物理开关及两帧读回结果。随后按[原材质、几何和双后端制作](source-material-workflow.md)完成专属着色。
