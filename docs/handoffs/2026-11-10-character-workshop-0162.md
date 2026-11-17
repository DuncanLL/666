# R116 人物 0.3.35 适配 Workshop Viewpoint 0.1.6.2

人物基线为 0.3.33（05ac59c）。本次在独立人物分支验收，与 RC4 的人物光影合包分开。

## 正常宿主

Steam Workshop 项目 3809306528，manifest 559614409211759918。使用 Steam 本地清单逐文件核对 17 个文件及 34,152,650 字节。原包包含 Viewpoint 0.1.6.2 与 42.22 版本范围。验证使用本机游戏 42.22.2 和 ZombieBuddy 2.3.4。原清单中的 Viewpoint.jar SHA-256 为 67f83b05a27c4112ce9edc596da7c491d51e9015862dccafa8495f0b9352625e。清单没有额外的 JAR 签名文件。

## 人物适配与验证

- RC4 的 ModelUploads.put 是试验宿主的额外提交入口。0.1.6.2 没有 ModelUploads，WorldRenderer 使用原来的 ModelPass.prepare(SceneData)，人物 Bridge 的该路径本来就提交人物的显示头数据。0.3.35 从人物 JAR 移除这一个 RC4 专用挂接，其余 JAR 条目逐字节沿用 0.3.33。
- 用正式宿主重编人物源码。通过 ZombieBuddy 正常入口检查 ModelCapture、Shaders、ModelPass 与 AnimationPlayer 四组挂接。游戏工坊目录校验、Lua 编译、12 个模型初始化与释放以及实际 VMD 组件检查通过，原生对象清零。
- 本机证据位于 build/workshop-0162-check，正式 VP 快照在 Viewpoint，人物完整工坊包在 Candidate/Workshop/R116。runtime-check.json、character-hooks-035.log 和 official-workshop.json 用于回查。
- 这是人物 MOD 的组件验收。专属 Native／Eclipse 光影随独立适配线验收，真实存档进游戏单独验证。

## 本机恢复

正式宿主文件按 Steam 清单装回订阅目录，人物 0.3.35 同步至本地 MOD、Steam 订阅目录和作者上传目录。RC4 宿主、最新 0.3.34 合包与两套测试光影缓存移出活动路径并备份。当前光影选用官方 Default，存档与默认 MOD 列表保持原字节。实际路径和回滚证据见 build/workshop-0162-check/restore-*/restoration.json。
