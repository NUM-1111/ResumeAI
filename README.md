# AgentCV · 最小工程验证

本检查点仅验证工程可构建启动、PostgreSQL/JDBC/Flyway、Vue/TypeScript/Vite 构建与 Boot 同源托管。尚无档案、工作区、简历导入保存或 AI 功能，不能作为第一业务切片验收。

## 路径与固定工具

- 主工作树：`/root/java/ResumeAI`；交接主副本在其 `核心交接` 目录。
- 验证数据库：`/var/lib/postgresql/resumeai-validation`，postgres 系统用户，权限 700，端口 `127.0.0.1:55432`。原 `/var/lib/postgresql/12/main` 不参与。
- Java 17.0.15、Maven 3.6.3、Python 3.8.10；脚本明确使用 `/root/.nvm/versions/node/v22.22.2/bin` 下的 Node 22.22.2/npm 10.9.7。
- Boot 3.5.16；Vue 3.5.45、TypeScript 5.9.3、Vite 8.3.4。前端完整传递依赖由 `frontend/package-lock.json` 固定；后端由固定 Boot parent/BOM 管理，实际树见 `docs/依赖树_ENV-02.txt`。
- PostgreSQL 12.22 仅限批准的合成数据验证，正式版本未确定。完整维护/安全核查尚未完成。

## 构建与验证（WSL 终端）

**先辨认终端：**提示符为 `PS ...>` 时是 Windows PowerShell，即使目录为 `\\wsl.localhost\Ubuntu\root\java\ResumeAI`，直接输入 `python3` 也不会自动改用 Linux。不要在 PowerShell 中执行下面未带 `wsl.exe` 的 Linux 命令。

### Windows PowerShell 入口（适用于当前 VS Code 终端）

在当前工程目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\agentcv.ps1 status
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\agentcv.ps1 db-start
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\agentcv.ps1 run
```

`Bypass` 只作用于这次 PowerShell 子进程，不更改系统执行策略。入口固定进入 Ubuntu/root 和 Linux 工程目录，不使用 Windows Python。也可不经过脚本直接执行：

```powershell
wsl.exe -d Ubuntu -u root --cd /root/java/ResumeAI --exec /usr/bin/python3 /root/java/ResumeAI/scripts/local.py db-start
wsl.exe -d Ubuntu -u root --cd /root/java/ResumeAI --exec /usr/bin/python3 /root/java/ResumeAI/scripts/local.py run
```

`run` 会持续占用终端并显示 Boot 日志，这是正常前台运行，不是卡住。等出现 `Started AgentCvApplication` 后再打开浏览器。停止按 Ctrl+C；如需停止数据库，应用退出后把最后一个参数改成 `db-stop`。

已有 JAR 时查看页面只需 `db-start` 和 `run`，不必每次重新构建前端。改前端后需先 `frontend` 再 `verify`，仅 `frontend` 不会更新已经打包的 JAR。

### Ubuntu WSL 终端内构建

```bash
cd /root/java/ResumeAI
python3 /root/java/ResumeAI/scripts/local.py db-start
python3 /root/java/ResumeAI/scripts/local.py frontend
python3 /root/java/ResumeAI/scripts/local.py verify
python3 /root/java/ResumeAI/scripts/smoke.py
```

`db-start` 首次只在已批准的空目录初始化，后续只启动/检查同实例。不调用系统 service，不自动启动原集群。前端首次 install 产生锁文件，后续使用 npm ci；后端 verify 不跳过测试，前端必须先构建。Maven 通过项目 `.local/maven-settings.xml` 显式采用现有 `https_proxy/HTTPS_PROXY`，不修改全局设置；未配置代理时使用默认直连。若网络失败，命令失败并显示原因，不更换仓库或技术栈。

外部执行器有 30 秒限制时可用 `python3 /root/java/ResumeAI/scripts/task.py verify` 等启动独立任务，读取 `.local/verify.log`。日志中的成功/失败才是结果，PID 不代表成功。普通 WSL 终端优先使用前台命令。

## 打开检查页面

```bash
python3 /root/java/ResumeAI/scripts/local.py run
```

浏览器访问 `http://127.0.0.1:8080/`，用户名 `operator`。仅本机验证用随机口令位于 `/root/java/ResumeAI/.local/credentials.json` 的 `operator` 字段，请在本机编辑器查看，不将该文件发到聊天、提交仓库或复制到日志。配置文件权限 600，目录权限 700；应用内存中使用 BCrypt 散列。当前是环境检查点的本地引导凭据安排，正式口令配置/凭据轮换在安全切片收口，不将其描述为完整安全验收。

登录后页面从真实数据库读取中文标记，可重新检查及退出。这里不需要 Vite 开发服务器或 Windows Node。应用仅绑定回环，不提供局域/公网模式。

前台运行按 Ctrl+C 停止应用。停止验证数据库：

```bash
python3 /root/java/ResumeAI/scripts/local.py db-stop
```

`smoke.py` 会自行启动和停止本轮 JAR，若 8080 已占用则失败，不终止未知进程。数据库数据保留，不执行删除或 Flyway clean。凭据丢失或目录状态不明时先检查，不自动清空重建。

## 记录与限制

当前结果见 `/root/java/ResumeAI/开发进度.md` 和 `/root/java/ResumeAI/docs/ENV-02_验证记录.md`。3 个集成测试连接真实 PostgreSQL；JAR 冒烟测试是真实 HTTP 和 Cookie 会话，不是浏览器渲染验收。浏览器视觉、Windows 主机到 WSL 的页面访问、业务 AT01—AT21、升级迁移、完整安全/备份恢复均未通过本检查点验证。