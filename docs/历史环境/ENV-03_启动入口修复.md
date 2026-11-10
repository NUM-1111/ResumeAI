# ENV-03 PowerShell 启动失败复现与修复

日期：2026-11-09。仅修启动入口，不实现业务。工程 `/root/java/ResumeAI`，无 Git 提交；差异为 agentcv.ps1、scripts/local.py、启动诊断/测试脚本、README 和记录。

## 原因与复现

用户终端为 Windows PowerShell，尽管当前目录为 WSL UNC 路径，直接输入 python3 仍由 Windows 解析。通过 `scripts/diagnose-windows.ps1` 在 Windows PowerShell 执行同一 Linux 脚本路径（使用 --help，无数据变更）实际复现：

- Get-Command python3 → `C:\Users\29436\AppData\Local\Microsoft\WindowsApps\python3.exe`。
- 原形式直接调用无输出，退出码 9009；Python 脚本并未执行。
- 显式 `wsl.exe -d Ubuntu -u root --cd /root/java/ResumeAI --exec /usr/bin/python3 ... --help` 输出帮助，退出码 0。
- 检查时 8080/55432 均无监听；项目没有新增 run 日志。数据库 server.log 最后是上一轮正常停止记录，不能误解为本次用户启动错误。

结论：已复现用户“输入命令后无输出返回”现象；用户贴出的内容本身没有报错正文，无法排除其另有未贴出的错误。但本机命令解析及失败退出码足以定位这一确定问题，不需安装 Windows Python或修改全局 PATH。

## 修复

- `/root/java/ResumeAI/agentcv.ps1`：明确选择 Ubuntu/root、工作目录及 Linux Python，透传失败退出码；status 可见运行环境和监听状态。无系统执行策略修改。
- local.py：拒绝 Windows Python；run 前检查 JAR、数据库端口及 8080 占用；不杀未知进程。前台持续显示输出并追加 `.local/run.log`，Ctrl+C 停止自身 Java 子进程。
- README 分开列出 Windows PowerShell 和 Ubuntu 命令；提示已打包时无需反复 frontend，前端改动需 verify 才进入 JAR。
- 状态检查仅表示端口监听，不冒充认证或业务就绪；实际应用是否成功以日志和 HTTP 为准。

## 测试结果

| 用例 | 命令/输入 | 预期与实际 |
| --- | --- | --- |
| ENV03-REPRO | Windows PowerShell 执行 diagnose-windows.ps1 | 原形式 9009、显式 WSL 0，复现通过 |
| ENV03-STATUS | PowerShell `agentcv.ps1 status` | 显示 Linux Python、正确目录、两服务停止，退出 0 |
| ENV03-FAIL | 数据库停止时 PowerShell `agentcv.ps1 run` | 明确 db-start first，退出 1，未启动 Java |
| ENV03-DB | PowerShell `agentcv.ps1 db-start` | 独立实例启动通过，不操作原集群 |
| ENV03-UNIT | `python3 /root/java/ResumeAI/scripts/test_local.py` | 4 项全部通过，覆盖平台检查、数据库未启动、端口占用拒绝 |
| ENV03-RUN | task.py startup-test → 真实 local.py run | 登录页 200，run.log 有真实 Boot 启动结果；测试只停止自身进程组 |
| ENV03-WINHTTP | Windows PowerShell 执行 test-windows-http.ps1 | Windows 主机 localhost 访问 WSL 登录页 200，通过 |
| ENV03-REGRESSION | `python3 /root/java/ResumeAI/scripts/smoke.py` | 对现有 JAR 重跑登录、CSRF、数据库与静态资源，最终 12 项全部 PASS |

额外失败记录：最初回归探测 bind(8080) 返回 Address already in use，随后 ss 已无监听；针对停止后 TIME_WAIT 的探测误判，给探测 socket 增加 SO_REUSEADDR，不设置 SO_REUSEPORT、不杀未知进程。直接重跑 smoke 最终 12 项通过。原失败仍在 .local/smoke.log，最终成功见本轮命令结果及 jar-smoke.log。

日志路径：应用 `/root/java/ResumeAI/.local/run.log`；数据库 `/var/lib/postgresql/resumeai-validation/server.log`；启动复测 `.local/startup-test.log`、`.local/launcher-test.log`。Windows Python 别名在执行脚本前失败，不会有应用日志；应查看 PowerShell `$LASTEXITCODE`。

尚未验证浏览器实际渲染/点击、真实交互终端的手动 Ctrl+C；已验证程序化 SIGINT 清理及 Windows HTTP 访问。未重新构建 Java：本轮无 Java/前端源码变化，沿用已测试 JAR；业务验收仍未开展。