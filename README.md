# AgentCV · S01 中文文本工作台

当前为 S01 中文纯文本业务切片：档案/工作区、材料检查确认、独立当前稿、自动保存与重开。等待用户操作验收；不接模型，不支持 DOCX/PDF、JD、删除恢复、历史恢复或备份。仅使用合成数据，PostgreSQL 12 不作为长期真实资料运行版本。

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

浏览器访问 `http://127.0.0.1:8080/`，用户名 **`operator`**。没有通用默认密码。请在 WSL 前台设置自己的 **6—32字符（含边界）**口令，输入不回显。旧密码不被自动替换，历史散列继续有效；下一次设置遵守新范围。中文等多字节输入另受BCrypt的72 UTF-8字节上限约束，超出明确拒绝，不截断：

```bash
python3 /root/java/ResumeAI/scripts/local.py set-password
```

然后启动/重启应用。不要把密码、`.local/credentials.json` 或其中数据库凭据发到聊天。测试使用临时内存口令，不修改你的登录口令。配置文件权限600、目录700。无注册、JWT或外发；局域模式、完整安全测试及备份未完成。

登录后左侧创建/选择档案及工作区，右侧粘贴或上传UTF-8 .txt（256 KiB内），保存为待检查材料，修正后确认，再从已确认材料或同档案其他工作区建立当前稿。中央编辑停止约900ms自动保存，失败保留页面文本。这里不需要 Vite 开发服务器或 Windows Node。应用仅绑定回环，不提供局域/公网模式。

前台运行按 Ctrl+C 停止应用。停止验证数据库：

```bash
python3 /root/java/ResumeAI/scripts/local.py db-stop
```

`smoke.py` 会自行启动和停止本轮 JAR，若 8080 已占用则失败，不终止未知进程。数据库数据保留，不执行删除或 Flyway clean。凭据丢失或目录状态不明时先检查，不自动清空重建。

## 记录与限制

完整操作步骤、测试数据、预期结果、接口核对及结果表见 [S01操作测试手册](docs/S01_操作测试手册.md)。执行后逐项填写实际结果，不能把手册存在视为测试通过。

当前结果见 `/root/java/ResumeAI/开发进度.md`、`/root/java/ResumeAI/docs/S01_交付与验收记录.md`；待办与责任分工见 `/root/java/ResumeAI/docs/阶段核查与决策清单.md`。S01 已有9项后端测试、3项前端状态测试、真实Chromium流程及12项JAR HTTP检查通过；Windows主机到WSL的HTTP访问已验证。浏览器自动化不替代用户操作验收，完整AT01—AT21、安全及备份恢复尚未完成。ENV-02/03记录保留其当时结论，不作为当前总状态。

GitHub：https://github.com/NUM-1111/ResumeAI 。远程使用SSH，2026-11-09核查远程main与本地dd3dc0c一致；后续同步以 `git status` 和 `git ls-remote --heads origin` 为准。日常提交后使用普通 `git push`，不默认强推。