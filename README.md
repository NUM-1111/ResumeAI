# AgentCV · 工作区简历与个人材料（M03）

**当前正在开发 M05**：已加入持续对话、每区输入草稿、上下文分隔和清空记录第一版，30 项后端测试和 M05 浏览器初测通过，但完整切片仍未收束。见 [M05 开发与验证](docs/切片开发/M05/开发与验证.md)与[初步体验](docs/切片开发/M05/页面体验.md)。下面保留早期切片说明，最新范围以本段和开发进度为准。

DeepSeek 密钥可放在项目根目录 `/root/java/ResumeAI/.env` 的 `DEEPSEEK_API_KEY=` 后，使用 `scripts/skeleton.py run` 自动读取。首次从 `.env.example` 复制，设置文件权限 600；已有文件不要覆盖。`.env` 不进入 Git，shell 同名变量优先，修改后重启生效。具体步骤见 [模型配置](docs/切片开发/M04/模型配置.md)。

**开发中更新：M04 正在接入 DeepSeek，尚未完成交付验证。**推荐 `deepseek-flash`，右侧已加入模型与资料范围、异步请求第一版。配置方式见 [M04 模型配置](docs/切片开发/M04/模型配置.md)，阶段与限制见 [开发与验证](docs/切片开发/M04/开发与验证.md)。下文 M03 为上一轮交付说明，不代表 M04 已通过；无密钥时不能发送，普通 verify/smoke 不会使用真实密钥。

**最新状态：M00—M02 已完成，M03 技术验证通过，待用户体验确认。**现已支持独立个人材料、名称搜索、重命名、手动编辑文本、原件下载及多工作区参考设置。AI 尚未开放。新增体验步骤见 [M03 页面体验](docs/切片开发/M03/页面体验.md)，实现与限制见 [M03 开发与验证](docs/切片开发/M03/开发与验证.md)。若应用仍运行旧包，保存后 Ctrl+C，再按下文 WSL 命令 `run` 启动新包；不用重设口令。

当前按阶段设计重新推进。登录、人物/工作区、粘贴及上传建立工作区简历、复制、自动保存与重开，以及独立个人材料管理已实现；不自动改稿，不调用模型。

## 首次使用（Ubuntu WSL，当前环境）

当前终端如显示 `root@Legancolie:~/java/ResumeAI#`，就是 Ubuntu WSL 的 Linux shell，直接执行下面的 `python3` 命令。无需 PowerShell、Windows Python 或修改 Windows PATH。

前置条件：Docker 服务已运行，已有仓库锁定的 PostgreSQL 镜像；WSL 中具备 Java 17、Maven、本地 Maven 依赖缓存及固定 Node 22.22.2 环境。当前脚本面向本机已准备的环境，不是全新机器的一键安装器。前端 `npm ci` 仍可能需要访问 npm registry，Maven 构建使用离线缓存。

1. 准备专属开发数据库、构建前后端并运行后端测试，成功后再继续：

```bash
python3 /root/java/ResumeAI/scripts/skeleton.py verify
```

2. 首次使用设置**新工程**口令：

```bash
python3 /root/java/ResumeAI/scripts/skeleton.py set-password
```

账号固定为 `operator`。口令需输入两遍，输入时不回显；允许 6—32 字符，同时不得超过 72 个 UTF-8 字节。仅保存 BCrypt 散列，不读取或自动迁入旧口令。日常启动不必重复设置；修改后需要重启应用生效。不要把口令发到聊天或提交到 Git。

3. 启动应用：

```bash
python3 /root/java/ResumeAI/scripts/skeleton.py run
```

等待终端出现 `Started AgentCvApplication` 后，打开 **http://127.0.0.1:8081/**，用 `operator` 和刚设置的口令登录。运行命令会占用当前终端，保持终端开启。

当前环境已经构建并验证过；数据库仍运行时，你首次体验只需设置口令，再启动，不必重跑 `verify`。

## 日常启动、停止和重新构建

当前 WSL 终端中，日常启动直接复制：

```bash
python3 /root/java/ResumeAI/scripts/skeleton.py run
```

如果 Docker/WSL 刚重启，先确保 Docker 服务已运行，再依次执行：

```bash
python3 /root/java/ResumeAI/scripts/m00_database.py
python3 /root/java/ResumeAI/scripts/skeleton.py run
```

下文的 `run`、`verify` 等名称均指 `python3 /root/java/ResumeAI/scripts/skeleton.py` 的子命令，不是直接输入的 shell 命令。当前使用 Linux 终端，不执行 `powershell.exe` 或 `.ps1` 文件。

- 已完成首次准备且数据库仍在运行时，只执行 `run`，不用每次构建或改口令。
- Docker/WSL 重启后，专属数据库不会自动启动。先启动 Docker，再在 WSL 执行 `python3 /root/java/ResumeAI/scripts/m00_database.py`，然后 `run`；也可执行 `verify`，但会同时构建和运行测试。
- 停止应用前等待页面显示“已保存”，再在运行它的终端按 **Ctrl+C**。数据库保持独立运行；下次启动仍可读取已保存简历。刷新或关页不保证恢复未保存文字。
- 修改代码后，先停止应用，再执行 `verify`；成功后重新 `run`。前端和后端统一打入 JAR，单独刷新浏览器不会加载尚未打包的代码。
- 若提示端口占用，检查是否已启动应用；不要为启动新实例而结束未知进程。当前入口没有 `stop`、`status` 或 `db-start` 子命令，不使用旧 `scripts/local.py`。

## M01 只需体验这条路径

1. 创建“演示人物”，再创建工作区“后端版”，岗位方向填“Java 后端”。
2. 在中间粘贴下面的合成简历，点击“预览并修正”，按需修改后点击“建立工作区简历”。
3. 修改正文，等待“已保存”，再刷新页面，确认回到相同工作区且文字仍在。
4. 左侧返回上层浏览时，正文应保留；选择其他人物/工作区才真正切换。有未保存内容时先保护输入。

```text
合成候选人
项目：订单演示系统
职责：参与接口开发，协助部署。
说明：仅用于软件体验，不是真实经历。
```

粘贴建立工作区简历不会添加个人材料，也不等于确认个人背景。上传和复制按 M02 指南体验；个人材料独立添加和选择参考，AI 尚未开放。

用户已依据 M01 页面体验指南确认测试没有问题，本片已完成。上述步骤保留供后续回归；反馈只需说明“走到哪一步、看到什么、期望什么”，不需要 F12、UUID 或数据库检查。故障、冲突、幂等和登录失效由自动化验证。详细说明见 [M01 页面体验](docs/切片开发/M01/页面体验.md)。

## 开发验证与 Ubuntu WSL 命令

在 Ubuntu WSL 终端可直接执行：

```bash
python3 /root/java/ResumeAI/scripts/skeleton.py verify
python3 /root/java/ResumeAI/scripts/skeleton.py smoke
python3 /root/java/ResumeAI/scripts/skeleton.py set-password
python3 /root/java/ResumeAI/scripts/skeleton.py run
```

| 命令 | 用途 |
| --- | --- |
| `verify` | 准备/启动专属数据库，执行 npm ci、类型检查、前端构建和 Maven clean verify，生成 JAR |
| `smoke` | 使用已构建 JAR 和运行中的专属数据库，在 18081 启动临时测试应用并执行浏览器回归，结束后停止测试应用 |
| `set-password` | 交互设置新工程操作者口令，仅保存散列 |
| `run` | 使用已构建 JAR 和运行中的专属数据库，在 8081 前台启动 |

`smoke` 是开发验证，不是日常使用前的必做步骤；需要 WSL 已有 Python Playwright 和 Chromium。它使用临时测试口令，不修改用户口令，但 `verify` 和 `smoke` 会向专属开发库写入合成测试数据，当前不会自动清理。接口测试通过不替代用户体验确认。

失败时查看终端错误；浏览器回归服务端日志在 `.local/skeleton-smoke.log`，后端测试报告在 `target/surefire-reports/`。不要提交凭据文件、日志、数据库或包含个人资料的截图。

## 数据隔离与文档入口

新实现使用独立容器 `agentcv-m00-postgres`、回环端口 `55434`、数据库 `agentcv_m00` 和 schema `m00_app`，不连接旧 `55433` 数据库。新工程数据库凭据和操作者散列保存在受控的 `.local/m00/`，不进入 Git。旧数据库、原件和凭据仍保留，不自动迁入。

- [阶段设计与开发计划](docs/README.md)
- [切片开发记录](docs/切片开发/README.md)
- [M01 开发与验证](docs/切片开发/M01/开发与验证.md)

旧源码基线与本地副本见 [旧实现参考](legacy/README.md)，骨架清理范围见 [清理记录](docs/切片开发/M00/工程骨架清理记录.md)。
