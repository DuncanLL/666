# 在干净检出中构建、验证和定位故障

从仓库根运行命令。新衣装的普通模型从[PMX 导入](pmx-import-workflow.md)开始，原程序着色从[源材质与几何](source-material-workflow.md)开始。能编译 JAR、能启动组件、能画对某个角色的某个 pass，各自需要不同证据。

## 构建两套渲染输入

1. 填齐 [paths.example.json](../../config/paths.example.json) 对应的 `.local/paths.json`。准备游戏、官方 VP JAR 及同版签名、ZombieBuddy、ECJ、当前人物参考 JAR、已核对的原生库及人物外置资源。Native 构建还读取 `render_support_asset_root` 下的显示资源，Eclipse 构建需要 `eclipse_support_reference_jar`。用来源清单的摘要逐项比对：普通下载的 Eclipse ZIP 不能代替指定基线 JAR。[Native 核心指纹](../../provenance/native-runtime.json) · [外置资源](../../resources/manifest.json) · [Eclipse 来源](../../provenance/eclipse-outline.json)
2. 按下列顺序从新检出构建。普通构建使用已验过的本机原生库。需要改 Rust/Bullet 或 Ozz 时，从 [build_native.py](../../tools/build_native.py) 的工具链和候选输出入手，再向 `build.py --native-candidate` 提供对应报告。精确复核旧发行核心才使用 `--compare-baseline` 或指纹匹配的 `--release-reference`。

```powershell
$steps = 'build.py', 'build_render_support.py', 'build_eclipse_support.py', 'build_combined_render_support.py'
foreach ($step in $steps) {
    & $py "tools/$step"
    if ($LASTEXITCODE -ne 0) { throw "Build failed at $step" }
}
```

| 产物 | 需要检查 |
|---|---|
| `build/GFL2Exilium.jar`、`build/build.json` | 人物代码、人物原生依赖指纹与这次检出一致。 |
| `build/render-support/build.json` | Native JAR、官方 host SHA、包内 Native 光影 ZIP 的键与 SHA。 |
| `build/eclipse-support/build.json` | Eclipse 参考 JAR SHA、目标宿主、着色覆盖点、最终 ZIP SHA。定点替换出现零个或多个位置时应核新基线。 |
| `build/combined-support/build.json` | 两个输入必须针对同一个宿主；合并两后端的类，解决已知同名实现，只保留一个安装入口，核对与官方宿主无同名 class。 |

[人物构建](../../tools/build.py) · [Native 构建](../../tools/build_render_support.py) · [Eclipse 构建](../../tools/build_eclipse_support.py) · [两后端合并](../../tools/build_combined_render_support.py)

## 生成可验证的候选

**进入这一步以前有三份额外输入。** 人物候选依赖 `asset_root` 与资源清单匹配。制作受保护的合并渲染 JAR 需要[规则中锁定版本和 SHA 的 ProGuard](../../render-support/release-protection.json)、原独立渲染项目的 `workshop.txt` 与 `preview.png`，以及含本次核心与对应全部角色包的本地 Workshop 目录。这个目录用来整理有效程序／资源目录。用新的输出名并保存各步骤的 build.json。示例中 `$shaderMetadata`、`$proguardZip`、`$roleWorkshop` 指向这些本机来源，`$version` 是本次明确选择的框架版本（例 `0.7.16`）。

```powershell
$character = 'build/workflow-inputs/character/GFL2Exilium'
$protected = 'build/workflow-inputs/protected'
$unified = 'build/workflow-run/Workshop/GFL2Exilium'

& $py tools/package_workshop.py --output $character
if ($LASTEXITCODE -ne 0) { throw 'Character packaging failed' }
& $py tools/protect_render_release.py --proguard-zip $proguardZip --metadata-from $shaderMetadata --catalog-workshop $roleWorkshop --output $protected
if ($LASTEXITCODE -ne 0) { throw 'Combined renderer protection/packaging failed' }
$jar = "$character/Contents/mods/GFL2Exilium/42/media/java/client/GFL2Exilium.jar"
$characterSha = (Get-FileHash $jar).Hash.ToLowerInvariant()
& $py tools/package_gfl2_unified.py --character-project $character --character-sha256 $characterSha --render-project "$protected/Workshop/GFL2RenderSupport" --render-report "$protected/protection-report.json" --version $version --output $unified
if ($LASTEXITCODE -ne 0) { throw 'Unified packaging failed' }
& $py tools/check_gfl2_unified.py --project $unified
if ($LASTEXITCODE -ne 0) { throw 'Unified startup check failed' }
& $py tools/check_character_pack_runtime.py workflow $unified $roleWorkshop
if ($LASTEXITCODE -ne 0) { throw 'Role pack registration check failed' }
```

`package_gfl2_unified.py` 检查人物 JAR、保护报告、宿主及两个后端输入的对应关系，输出必须是仓库 `build/` 下面新的 `Workshop/GFL2Exilium`。测试用的普通人物候选可能只有本地预览图及临时元数据；正式项目身份和封面按既有作者目录另行核对。保护阶段的私有映射、种子与研究资料留在本机。联包验证包括正常加载与宿主晚到，角色包验证覆盖停用、启用、旧分片的部分匹配与清场。两项均为受控检查。[保护与封装](../../tools/protect_render_release.py) · [人物与双后端合包](../../tools/package_gfl2_unified.py) · [组件启动](../../tools/check_gfl2_unified.py) · [角色包回读](../../tools/check_character_pack_runtime.py)

## 实际画面与故障定位

| 现象 | 先做哪个区分 | 要留的证据 |
|---|---|---|
| 普通模型就失败、左右手错或腿扭 | 比对真实 PMX SHA、顶点与骨数、源骨顺序、中立 QPS、父空间变换、颈高比例、掌面及弯臂校准 | 参考与弯臂两帧，再加行走、蹲、持物和切回空手；记录出错的第一根骨。 |
| 普通衣装可用，选 Native/Eclipse 后没有专属材质 | 对照实际选中的 pack、模型 SHA、八字段分片、材质重映射、顶点数及已提交 part；检查首个资源/着色错误 | 从一个指定 part 追到程序、sampler、实际绑定的纹理 SHA 和目标 framebuffer。 |
| 只有眼睛、发丝、衣服半透明层错误 | 看源 pass、queue、混合、alpha、写深度与 stencil，再看 UV 岛、切线符号及 renderer 根 | 同相机与姿态比较深度、阴影、早期输出和最终颜色，检查关闭与开启对应配件。 |
| 开 TAA 时人物大小不对，或换显卡黑屏 | 比较早期／最终 clip-space、render scale、抖动与宿主保留采样槽。读第一条驱动错误，如 varying 冲突或 GLSL 关键字 | 留驱动、宿主 JAR 和 pack SHA、include/宏展开后的 VS/FS（需要时含几何／细分阶段）及链接日志。 |
| 回归只在切人物、切包、暂停后出现 | 检查 model/session、旧 IrisPrograms/IrisTargets、Native 帧所有权、上一帧姿态和资源退役 | 记录进入、退出、再进入，分别看 GPU 内存、Java/原生资源计数与实际帧耗。 |

在实际宿主上分别检查 Native 和 Eclipse 的默认设置、打开参数页后的运行时 UBO、切走再切回。至少对照正面、侧面、背面，白天、夜间、室内和手电，再运动一段看上一帧位置及阴影。原游参考拍摄要锁定衣装、表情、机位、光照和曝光。更改了公共人物路径还要看老角色。驱动编译以实际 host 展开的 GLSL 为准；可用 [check_original_stage_links.py](../../tools/check_original_stage_links.py) 检查有对应外置原资料的阶段组合。最终用 [check_render_release.py](../../tools/check_render_release.py) 对当前构建和证据 JSON 逐项核验，三厂公开兼容声明需要 `--public` 的条件。[发布标准](../render-release-standard.md) · [曾由玩家日志定位的案例](rendering.md)

## 给下一位接手者的交付记录

在项目外保存一份记录，至少写入：源码 commit、PZ/VP/ZombieBuddy 与 GPU/驱动、源文件及对应授权来源、稳定 ID 和完整 PMX SHA、骨／顶点／材质数量、材质来源台账、纹理及程序 SHA、映射未解决的范围、各后端的 build.json 与启动／绘制报告。把离线标定、材质二进制回读、宿主绘制、安装、游戏内验收和发布分别填结果及证据路径；对照的原游输入缺失时直接填缺什么。后续 AI 可以从最早未通过的一步继续。
