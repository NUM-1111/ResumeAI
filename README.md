# AgentCV · S01 中文文本工作台

S01基本交互与S02安全回收已获用户基本体验认可。开发库为官方PostgreSQL17.11，12保留停用。S03已实现DOCX/文本层PDF提取、材料用途和显式辅助引用，待用户新增体验；见 [S03使用](docs/用户指南/S03_导入与引用体验.md)。不接模型，不支持OCR、JD、历史恢复及完整备份维护；继续仅用合成数据。

## 路径与固定工具

- 主工作树：`/root/java/ResumeAI`；交接主副本在其 `核心交接` 目录。
- 开发数据库：Docker容器 `agentcv-postgres17`、独立卷 `agentcv-postgres17-data`，仅 `127.0.0.1:55433`，镜像摘要见 `/root/java/ResumeAI/postgres17.lock.json`。旧12目录 `/var/lib/postgresql/resumeai-validation` 停用保留，原 `/var/lib/postgresql/12/main` 不参与。
- Java 17.0.15、Maven 3.6.3、Python 3.8.10；脚本明确使用 `/root/.nvm/versions/node/v22.22.2/bin` 下的 Node 22.22.2/npm 10.9.7。
- Boot 3.5.16；Vue 3.5.45、TypeScript 5.9.3、Vite 8.3.4。前端完整传递依赖由 `frontend/package-lock.json` 固定；后端由固定 Boot parent/BOM 管理，实际树见 `docs/历史环境/依赖树_ENV-02.txt`。
- PostgreSQL17.11已完成导入和业务回归；完整维护/安全及通用备份恢复仍未完成。Docker需运行；WSL重启后执行db-start启动已有实例，不自动拉取镜像或回退12。

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

`db-start` 只启动已核验标签及镜像的17容器，不创建未知实例，不启动12。前端使用npm ci；后端verify不跳过测试，前端必须先构建。Maven通过项目.local设置使用终端代理；Docker服务代理经用户单独批准配置，原配置已有备份。下载失败不静默换源。迁移详见 [17迁移记录](docs/当前切片/PostgreSQL17_迁移记录.md)。

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

登录后左侧逐层选择档案、工作区和材料，中央粘贴或上传UTF-8 .txt（256 KiB内），右侧保存待检查材料、确认并建立当前稿；确认材料也可预览后替换已有稿。中央编辑停止约900ms自动保存，失败保留页面文本。顶部回收站恢复软删除对象。无需Vite开发服务器或Windows Node；仅回环访问。

前台运行按 Ctrl+C 停止应用。

如果终端已丢失、或重复run提示已经运行，可在另一个WSL终端安全停止本工程，再重新启动：

```bash
python3 /root/java/ResumeAI/scripts/local.py status
python3 /root/java/ResumeAI/scripts/local.py stop
python3 /root/java/ResumeAI/scripts/local.py run
```

stop只向已核对的本工程8080监听Java进程发SIGTERM，最多等待15秒，不强杀未知进程、不停止数据库。重复run已确认本工程在运行时只显示网页地址，不启动第二份，也不代表新密码或新JAR已被加载；改密码/构建后必须stop再run。PowerShell入口同样支持stop。

停止验证数据库：

```bash
python3 /root/java/ResumeAI/scripts/local.py db-stop
```

`smoke.py` 会自行启动和停止本轮 JAR，若 8080 已占用则失败，不终止未知进程。数据库数据保留，不执行删除或 Flyway clean。凭据丢失或目录状态不明时先检查，不自动清空重建。

## 记录与限制

文档总入口：[文档导航](docs/README.md)。用户只参考 [页面验收简版](docs/用户指南/S01_用户验收简版.md)，不要求F12、UUID、接口或数据库核对；技术测试由助手执行。已有反馈不用重新记录，页面难用也是验收阻塞项。

当前结果见 `/root/java/ResumeAI/开发进度.md`，数据库迁移见上述17记录。17上16项后端、12项JAR HTTP和五组Chromium通过，前端状态3项通过；完整AT01—AT21、安全及备份恢复尚未完成。历史ENV和各切片旧记录保留当时范围，不作为最新总状态。

GitHub：https://github.com/NUM-1111/ResumeAI 。远程使用SSH，2026-11-09核查远程main与本地dd3dc0c一致；后续同步以 `git status` 和 `git ls-remote --heads origin` 为准。日常提交后使用普通 `git push`，不默认强推。
