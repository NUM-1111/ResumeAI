# ENV-02 最小工程可运行验证记录

日期：2026-11-08；执行环境：Ubuntu 20.04.6 / WSL 2，工程命令 root，数据库进程 postgres；仅合成标记，无真实简历、模型或搜索调用。

## 版本与差异范围

本轮未初始化 Git、未提交。源码范围：`/root/java/ResumeAI/pom.xml`、`src/`、`frontend/`、`scripts/`、忽略规则、运行及进度文档。源码 SHA-256 清单在同目录 `源码校验_ENV-02.json`；本轮没有操作旧项目文件或原数据库集群。

| 组件 | 实际版本/来源 |
| --- | --- |
| Java / Maven | OpenJDK 17.0.15 / Maven 3.6.3（现有工具） |
| Boot / Framework / Security | 3.5.16 / 6.2.19 / 6.5.11（Boot parent/BOM） |
| Flyway core / PostgreSQL 模块 | 均 11.7.2（Boot BOM） |
| PostgreSQL JDBC | 42.7.11（Boot BOM） |
| PostgreSQL 服务端 | 12.22，仅隔离验证 |
| Node / npm | 22.22.2 / 10.9.7（现有 NVM 绝对路径） |
| Vue / TypeScript / Vite | 3.5.45 / 5.9.3 / 8.3.4（package.json 精确版本及 package-lock.json） |

完整后端树见 `依赖树_ENV-02.txt`，数据库实际核查见 `数据库核查_ENV-02.txt`。Boot/Spring/Flyway 常用许可证为 Apache-2.0，Vue/Vite 为 MIT、TypeScript 为 Apache-2.0、PostgreSQL/JDBC 为 PostgreSQL/BSD 类许可证；尚未对全部传递依赖逐项完成许可证与 CVE 审计，不能作为完整合规结论。

## 命令、预期及实际

下列路径均在 WSL 执行，项目根 `/root/java/ResumeAI`。工具传输有 30 秒上限，因此长任务实际通过 `scripts/task.py` 后台运行并读取结果；前台复现入口在 README。

| 用例 | 命令 | 预期 | 实际 |
| --- | --- | --- | --- |
| ENV02-DB | `python3 /root/java/ResumeAI/scripts/local.py db-start` | 初始化独立空目录，peer 管理，SCRAM 应用账号，仅回环 55432 | 通过；原 12/main 保持 down |
| ENV02-FE | `python3 /root/java/ResumeAI/scripts/local.py frontend` | install 生成锁，再 ci 可重复构建；TS 检查及 Vite 构建成功 | 两次通过，第二次 npm ci；JS/CSS 哈希名一致 |
| ENV02-BE | `python3 /root/java/ResumeAI/scripts/local.py verify` | 编译、连接数据库、V1 迁移、集成测试、打包成功 | 最终 BUILD SUCCESS；3 tests，0 failures/errors/skipped |
| ENV02-MIG | 上述集成测试 migrationIsValidAndRepeatableWithoutDuplicateRows | 空库 V1，后续 migrate 为零新迁移，中文标记只有一行 | 通过，schema version=1 |
| ENV02-SEC | 上述集成测试另两项 | 未登录 401，CSRF no-store，缺 token 登录/退出 403，会话可读 API/静态页 | 通过；不是完整业务安全验收 |
| ENV02-HTTP | `python3 /root/java/ResumeAI/scripts/smoke.py` | 启动真实 JAR，验证 Cookie 登录及同源资源，结束时停止 JAR | 两轮均 12 项 PASS；最后一轮对最终 JAR 复测 |
| ENV02-RESTART | db-stop → db-start → verify/smoke | 数据持久保留，无重复迁移 | 通过，中文标记及 V1 保留 |
| ENV02-TREE | `python3 /root/java/ResumeAI/scripts/local.py dependencies` | 固化实际后端依赖树 | BUILD SUCCESS，复制入 docs |
| ENV02-AUDIT | Node 22 PATH 下 `npm --prefix /root/java/ResumeAI/frontend audit --json --registry=https://registry.npmjs.org --fetch-timeout=15000 --fetch-retries=0` | 查询当前锁依赖已知漏洞 | registry 返回 total=0；仅限该次 npm 审计范围 |
| ENV02-EVIDENCE | `python3 /root/java/ResumeAI/scripts/evidence.py` | 数据库核查、源码清单、实际凭据日志扫描 | 已执行；首次发现并脱敏本地失败日志中的凭据；最后复核无新增命中 |

原始项目日志在 `.local/frontend.log`、`.local/verify.log`、`.local/dependencies.log`、`.local/smoke.log` 和 `.local/jar-smoke.log`，测试报告在 `target/surefire-reports`。这些本地文件不提交；敏感值只脱敏，不删除失败记录。

## 失败、定位及修复

1. initdb 跨用户读取 `/dev/stdin` 失败：改为管理账号本地 peer、应用账号独立 SCRAM。切换用户时明确 cwd 为专用数据库目录，未放宽 `/root`。
2. Maven 扫描阶段阻塞：未继承环境代理；生成项目 `.local/maven-settings.xml`，不改全局。一次 Jackson 制品下载超时，经 HEAD 200 和有限重试后通过，没有更换镜像或栈。前端元数据查询也曾超时，后续获取与构建成功。
3. 认证集成测试首次 3 项中 1 项错误：BCrypt 缺少委托编码器前缀；增加 `{bcrypt}` 后真实认证通过。
4. 再次测试 1 项失败：MockMvc 不执行 welcome-page 内部转发，直接断言 `/` 正文不正确；分别断言转发 `index.html` 和静态正文，并新增真实 JAR HTTP 验证，而非删除静态页面验证。
5. 失败时 MockMvc 请求诊断曾打印本地登录口令：关闭自动请求打印，历史日志值替换为 `[REDACTED_LOCAL_CREDENTIAL]`，保留失败堆栈与原因。无真实用户资料输入。
6. Windows 执行器带引号的 psql `-c` 命令解析失败；后续改为 Python 子进程参数数组及 stdin SQL 获取确定结果。不把失败命令算通过。

## 官方依据与边界

- Boot 3.5.16 制品及 Java/Maven 要求已查阅并由构建验证：https://docs.spring.io/spring-boot/3.5/system-requirements.html 。3.5 系列当前维护期限仍待核验；过程中曾错误断言 3.5.16 是最后开源版本，已明确撤回，不作为版本决策依据。
- Vite 8.3.4 registry 元数据 Node 要求 `^20.19.0 || >=22.12.0`；已有 Node 22.22.2 实际构建通过：https://registry.npmjs.org/vite/8.3.4 。Vue/TypeScript 使用各自精确 registry 版本元数据，不使用浮动 latest 写入配置。
- PostgreSQL 官方 https://www.postgresql.org/support/versioning/ 标示 12.22、Supported=No、终止支持 2024-11-21；12 仅限本次批准验证，不建议用真实资料长期运行。Flyway 实测可迁移不等于数据库仍有安全维护。

## 尚未运行/实现

- 浏览器视觉和实际 JS 点击交互、Windows 主机 localhost 到 WSL 页面访问：无浏览器工具验证，不用 HTTP 获取代替。
- 完整安全门禁：登录限流、口令轮换/仅散列持久配置、局域 HTTPS、会话过期和固定攻击测试；当前仅本机环境引导凭据，不作为完整安全交付。
- 业务 AT01—AT21、事务/人物隔离/保存冲突、业务接口与 DDL、DOCX/PDF、模型/搜索、备份恢复均未执行或实现。
- 升级迁移仅有 V1 重复 migrate，尚无 V2/故障迁移测试；数据库迁移机制起步通过不代表升级/恢复通过。
- 新数据库正式版本、OS/JDK 维护与补丁计划、全部后端依赖 CVE/许可证核查待收口，不擅自升级或换栈。

本检查点交付后停止推进业务，交用户检查。运行命令见 `/root/java/ResumeAI/README.md`。