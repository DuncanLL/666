# 源码导航

[维护手册](maintenance/README.md) 记录游戏状态、公共动作修正、整备室与多人以及双光影的运行链路。维护状态和按版本查找的记录见 [版本记录](../CHANGELOG.md)。框架及角色订阅入口见 [项目首页](../README.md)。

| 内容 | 位置 |
|---|---|
| 人物注册、模型资料、衣装和配件 | [核心资源](../game/mod/42/media/gfl2_pmx) · [角色包](../game/packs) · [整备室资料](../game/mod/42/media/ui/GFL2ReadyRoom) |
| 人物姿态、网格和物理 | [动作与姿态](../game/java/gfl2/motion) · [原生核心](../native) |
| 整备室、声音与多人 | [游戏 Lua](../game/mod/42/media/lua) · [多人协议记录](multiplayer-framework.md) |
| Native 和 Eclipse | [渲染支持](../render-support) · [光影发布检查](render-release-standard.md) |
| 构建、验证和发布记录 | [工具](../tools) · [结构化发布记录](../provenance/releases) · [历史笔记](handoffs) |

安装路径与发布目录从本机配置和实际 `mod.info` 核对。整理源码时，现装、Steam 上线及用户实机结果各按其记录描述。日常维护从正式主分支建立任务分支，合并前核对所用宿主和候选文件指纹。
