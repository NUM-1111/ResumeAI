# PostgreSQL17 迁移执行记录

用户已接受S02基本体验；批准独立官方PostgreSQL17、保留12而不再作为开发库，并在Docker下载阻塞后明确批准服务代理配置及重启。

## 已执行环境变更

- Docker原配置备份 `/etc/docker/daemon.json.agentcv-backup-20261110-200718`。
- Docker28.0.1使用当前本机HTTP CONNECT代理，停用旧镜像源列表，其余配置保留。dockerd配置校验、重启通过，重启前无运行容器。代理不可用时镜像下载会受影响，本地数据库运行不要求访问镜像站。
- 官方`postgres:17-bookworm`已下载并锁定为 `postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994`，实际17.11（Debian17.11-1.pgdg12+2）。口头中间误报17.7，已以实际SHOW版本纠正。

## 迁移程序边界

`scripts/migrate_postgres17.py`拒绝覆盖已有锁/容器/卷；先停止本工程应用写入；保存12逻辑dump与materials目录一致副本到.local受控目录，再恢复到独立容器和卷，初步核对表数量。PG12 dump格式可用于17 pg_restore，不能反向用12工具备份17。后续17备份使用容器17工具。

后续要验证内容摘要/原件校验、V1—V3迁移和业务回归。迁移未完成时不称切换成功。旧12数据目录不删除，验证完成后停止旧验证实例，不动系统原集群。镜像锁文件只提交制品/版本信息，不提交本地备份路径、数据统计或凭据。

## 实际迁移及验证

- 停止本工程应用后，12逻辑dump与材料副本保存 `/root/java/ResumeAI/.local/migration17-20261110-201146`，目录700、dump600；manifest记录dump摘要。数据库凭据不输出、不提交。
- 独立容器agentcv-postgres17、卷agentcv-postgres17-data，回环55433。导入成功；17上Flyway V1—V3校验、后端16项、HTTP12项、浏览器五组通过。
- `verify_migration17.py`核对10张表中全部旧记录保持一致（17新增测试记录允许），材料storage_key对应文件SHA256全部一致；初次时区表达不同造成误报，统一UTC后逐行比较通过，无正文输出。
- 旧验证实例已通过pg_ctl fast停止；目录及数据原样保留，原12/main未操作。local.py的db-start/stop、应用默认URL、audit/evidence已切17，不自动回退12。用户口令散列不变。
- scripts/test_local.py12项、test_postgres17.py3项通过。启动17需Docker已运行，restart=no，WSL重启后用db-start显式启动。
- 已实际执行db-stop再db-start，17正常重启；旧12未自动启动，数据保留。应用保持停止，用户可以直接run。

未完成：全新空库V1—V3复演、通用备份恢复维护命令和周期运维、OS/依赖安全全量核查。本次迁移导入证明该副本能导入，不替代完整恢复演练。12是冻结的旧副本，不包含17后续改动，不能无损直接回退。

下一步已进入S03契约和实施准备，尚未增加解析依赖，见同目录S03实施计划；不宣称DOCX/PDF或引用已支持。