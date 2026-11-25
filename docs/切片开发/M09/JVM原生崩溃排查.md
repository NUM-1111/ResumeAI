# JVM 原生崩溃排查

用户报告服务进程退出，原始证据保留于本机 hs_err_pid14159.log、replay_pid14159.log，不上传完整报告/核心转储（可能含业务正文或秘密）。本次不是供应商超时或空回答。

## 问题定义与证据

- OpenJDK 17.0.15+6-Ubuntu-0ubuntu120.04，Ubuntu20.04 WSL，Intel i9-13900HX。
- 报告时间2026-11-25 07:22:47 CST，进程运行486秒后，C2 CompilerThread0 原生SIGSEGV，JVM随后终止，Python看到SIGABRT。
- Current CompileTask：org.springframework.web.servlet.handler.AbstractHandlerMapping::getHandler，C2级别4。
- 原生堆栈 PhaseLive::add_liveout → PhaseLive::compute → PhaseChaitin::Register_Allocate → Compile::Code_Gen。
- 直接故障层是HotSpot C2编译器，不是Java业务抛异常；不能靠业务catch恢复。被编译的方法不等于Spring实现本身有缺陷，也不能仅凭此报告排除硬件/WSL/系统因素。
- 本机只有17.0.15及Java8，apt-cache policy已缓存候选仍为17.0.15。未擅自系统升级、切Java8或提交核心转储。

## 本轮规避

scripts/skeleton.py.environment 对本项目子进程追加 JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1，覆盖日常run、Maven测试、浏览器临时JVM和隔离恢复进程。保留C1即时编译，禁用此次出错的C2路径，不修改全局Java配置；直接java -jar不经过脚本时不会自动带上规避参数。环境已有其他Java选项会保留，最终追加本项目设置。

这不是已经修复JVM内部缺陷的声明。代价是失去C2峰值优化，当前单操作者MVP先以稳定性为目标，不作吞吐承诺。后续选择受支持的较新Java17补丁构建并在隔离环境回归，再评估移除此规避；不承诺任何特定JDK版本必然修复同一故障，也不依据CPU型号直接断言硬件损坏。

新增忽略 core/core.*、hs_err_pid*、replay_pid*，原证据保留本地不删除。无模型自动重试，崩溃时CALLING重启后按UNKNOWN处理；内存尚未保存回答可能丢失，应如实提示，不能恢复为伪造成功。

## 验证范围

执行新配置下前后端构建/后端测试、独立JAR启动恢复及浏览器业务回归，确认参数由子JVM实际接受。不为复现崩溃启用不受信任的compiler replay，不自动发送用户问题或付费调用。短期回归通过仅证明规避配置与业务兼容，不能替代长时间稳定性观察。

执行结果：新配置下构建与55项测试通过；实际JAR隔离恢复实验通过（.local/m08_2edac1902240/result.json），M07真实HTTP/浏览器回归通过，文档和空白检查通过。首次测试因子JVM在stderr输出Picked up JAVA_TOOL_OPTIONS，原跨进程锁测试把合并输出第一行当LOCKED而失败；改为独立丢弃子进程stderr，不改变锁断言，回归通过。没有重现原C2崩溃，也没有据此声称已永久修复运行时。