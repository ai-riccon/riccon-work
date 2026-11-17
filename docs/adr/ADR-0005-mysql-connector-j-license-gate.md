# ADR-0005：MySQL Connector/J 许可风险与发布门禁

Status: accepted
Scope: 临时风险接受，仅限开发/测试/内部演示；对外分发前必须重开 DEC-0003
Date: 2026-11-17

## Context

`apps/api` 使用 `com.mysql:mysql-connector-j:9.7.0`（Spring Boot 4.1.2 BOM 解析结果，`runtime` scope）连接 MySQL 8.4.13。该驱动采用 GPLv2 + FOSS exception 双许可；把驱动随产品一起分发给客户（私有化部署、一体机、离线安装包）不属于 FOSS exception 的典型适用场景，需要 Oracle 商业许可或替换驱动。

合同 `constraints.licensing` 已写明该风险。DEC-0003 由用户选择 **C：暂用 Connector/J，发布前必须重新决策（商业许可或替换驱动）**，并明确不推荐 C。本 ADR 把该临时选择的适用范围、技术约束和发布门禁固定下来，避免后续会话重新争论“现在要不要换”。

当前项目处于 M01（greenfield），尚无对外交付；开发与演示只使用合成数据和客户内网网关（合同 `integrations.external_apis`）。

## Decision

1. **继续使用** `com.mysql:mysql-connector-j` 9.7.0，由 Spring Boot BOM 管理，`runtime` scope；不写任何驱动专有 API。
2. **适用范围**：开发、测试、内部演示与内部 PoC 评估（不向客户交付制品）。**禁止**把含该驱动的制品交付到客户环境（含客户内网 PoC）、一体机、离线安装包、托管交付或任何客户可获得的制品。
3. **发布门禁**：任何对外分发前，必须重开 DEC-0003，由用户选择：
   - A：切换 `org.mariadb.jdbc:mariadb-java-client` 3.x（LGPL-2.1）；
   - B：采购 Oracle MySQL 商业许可；
   - 其他经用户批准且有书面许可依据的方案。

   回答必须绑定具体 `release_id`（`answer_kind: approve`）。没有该决策时 release 检查失败，B-01（`docs/security/THREAT-MODEL.md`）保持打开。

4. **降低切换成本**：
   - 业务代码只使用 Spring JDBC/`JdbcClient` 与标准 SQL，不引用 `com.mysql.*` 类型；
   - 连接串集中在 `application.yml` 的环境变量（`RICCON_DB_URL`），不散落在代码；
   - 不使用 MySQL 专有语法作为业务正确性依赖；确需使用时必须写测试并在本 ADR 记录；
   - 迁移脚本保持 ANSI 兼容，避免只有 Connector/J 才能执行的语句。
5. **发布清单**：每次对外发布必须列出所有 runtime 依赖及许可证，MySQL 驱动单独标注；清单由 Lead 与法务确认。

## Consequences

- 正面：M02 不被法务采购阻塞；数据访问层保持驱动无关，未来切换成本可控。
- 负面：法务风险被后置；若发布前才切换，可能在 M09 引入兼容性回归；必须在每个 release 前检查 B-01。
- 约束：不得以“内部使用”“仅演示”为由把含驱动的制品交给客户；不得把驱动单独再分发；ADR 被新决策替代前，release 记录必须显式引用本 ADR。

## Alternatives

- **A：MariaDB Connector/J 3.x（LGPL-2.1）**：不引入 GPL 分发义务，许可最省心。需要 spike 验证：`caching_sha2_password` 认证、`jdbc:mariadb:` 与 `jdbc:mysql:` 的 URL 兼容（`permitMysqlScheme`）、Flyway 12 对 MySQL 的方言识别、时区与批量语义。DEC-0003 推荐 A，但用户选择 C。
- **B：Oracle 商业许可**：保持驱动与行为不变，有采购成本与合同周期；适合客户指定 MySQL 且不愿承担驱动切换风险的场景。
- **继续使用且不决策**：只允许停留在开发/演示；作为发布路径不可接受，等于把 GPL 义务带进交付物。
- **换数据库（PostgreSQL）**：超出本项目范围，且客户环境已锁定 MySQL 8.4。

## Evidence

- `.riccon/decisions/DEC-0003.md`（用户选择 C，2026-11-17）
- `apps/api/pom.xml`：`com.mysql:mysql-connector-j`，`runtime`
- `./mvnw -pl apps/api dependency:tree -Dincludes=com.mysql:mysql-connector-j` → `9.7.0`（本地离线执行可加 `-o`）
- `docs/security/THREAT-MODEL.md` 第 8 节 B-01、第 9 节 R-05
- 合同 `constraints.licensing` 与 `stack.versions.mysql`
