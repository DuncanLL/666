# 0.3.1：切换人物时错误释放贴图内存

2026-11-06，F 测试环境连续崩溃。三份最新 `hs_err`（27208、37844、21104）均在 `TextureFactory.bake` 写原生缓冲区时发生 `EXCEPTION_ACCESS_VIOLATION`；同日还出现过游戏 VBO 写缓冲区时崩溃。这些是内存破坏的表现，不能仅根据最终崩溃栈认定像素循环或胸部求解器是错误写入源。

## 根因和修复

`TextureFactory.decode` 用 `pixels.get(rgba)` 复制图片，使 STB 返回缓冲区的 position 移到末尾。实际游戏携带的 LWJGL `STBImage.stbi_image_free(ByteBuffer)` 使用 `MemoryUtil.memAddress(buffer)`，即当前位置的地址。释放时因此传入图片末尾地址，而非分配起始地址，破坏原生堆；之后其它贴图或顶点分配才可能崩溃。

仅将复制改为绝对读取 `pixels.get(0,rgba)`，保持 position 与分配起始地址不变。没有增加角色识别分支、定时器或每帧检查，也没有更改图片像素、尺寸、材质、动作或物理算法。0.3.1 启动标记用于确认实际加载版本。

接口实现参考：[LWJGL STBImage 源码](https://github.com/LWJGL/lwjgl3/blob/master/modules/lwjgl/stb/src/generated/java/org/lwjgl/stb/STBImage.java)。同时已反编译本机实际游戏 JAR 的该方法核对，结论不依赖网上版本恰好一致。

## 复现和验证

- 修前：独立隐藏 GL 检查使用实际 PMX、Java、Rust/Bullet、STB 和贴图上传链，约 6 秒复现相同 `TextureFactory.bake` 崩溃，记录在 `build/texture-probe/hs_err_pid15500.log`。
- 修前额外启用 LWJGL 内存诊断：`TextureFactory.decode → STBImage.stbi_image_free` 报 `The memory address specified is not being tracked`，并报告对应 STB 图片分配未释放。诊断回调可能只打印错误而进程仍返回 0，检查工具同时审查日志，不能只看退出码。
- 修后：妮基塔连续 6 次完整加载、上传、释放通过。随后正式回归覆盖全部 11 份 PMX、2 轮共 22 个会话；没有非法释放或 allocator 泄漏报告，未变材质复用已有纹理，结束时模型原生对象和胸部 world 均为 0。
- 正式命令：`python tools/check_texture_runtime.py --game-root "<实际游戏目录>"`。只创建本进程隐藏 GL 上下文，不启动或操控游戏。报告位于 `build/texture-free-fix/runtime-check.json`，完整日志在同目录 `runtime.log`。
- F 与 Steam 实际游戏 JAR SHA 相同。已对两端人物 MOD 安装 JAR 与 `mod.info` 并回读；其它资源不替换。新旧所有内嵌原生组件逐一哈希相同，保留已认可胸部物理。JAR 比修前增加 24 字节。
- 安装和小文件回退记录在 `build/texture-free-fix/install.json`。候选工坊项目同步更新，但未上传。

此前组件检查只覆盖模型/物理解算，没有覆盖真实图片上传和释放；这次已将该路径加入可重复回归。受控组件检查通过不等于真实游玩、长期运行、多人或所有历史崩溃已验收。用户需要完全重启游戏后加载修复 JAR。
