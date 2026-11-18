---
review_id: REV-TASK-0043-001
module_id: M03
task_id: TASK-0043
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0044_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [1b7905c]
created_at: 2026-11-18T19:50:13+08:00
findings:
  - id: F-001
    title: "B4 作用域测试未显式覆盖 DEPT-003 的产物预签名下载 404"
    severity: P2
    status: accepted
  - id: F-002
    title: "三处生产注释仍保留 M02 创建人私有语义，与 M03 空间访问冲突"
    severity: P3
    status: accepted
  - id: F-003
    title: "workspace 包外可见性 SQL 守卫只覆盖固定的 w. 别名形态，属于启发式守卫"
    severity: P3
    status: accepted
  - id: F-004
    title: "成员移出部门后的内容级 404 只由空间谓词测试间接覆盖，B4 未新增直接用例"
    severity: P3
    status: accepted
  - id: F-005
    title: "审批列表与决策仍为租户级，未随报告任务工作空间收窄"
    severity: P3
    status: accepted
---

# TASK-0043 独立评审：M03-B4 内容贯通与旧谓词清理

## 结论

**verdict: pass**（无开放 P0/P1）。B4 的代码提交是 `1b7905c`，证据/状态提交是 `953d59b`，评审时 HEAD 为 `3e25194`。会话、文件、报告、产物、运行详情的主要读取与写入入口都已先按对象 `workspaceId` 调用 `WorkspaceService.requireAccessible`；`FileJdbc.WORKSPACE_VISIBLE`、`ConversationJdbc` 的 owner-only 查询和 `RunDetailService` 的 CHAT owner-only 分支已删除。共享空间成员可读取会话/文件/报告/产物/运行并按 ADR 追加消息，标题修改、会话删除、文件删除仍保持创建人/上传者对象级权限，跨部门与跨租户统一 404。

**B4 可以由 Lead 标记 done**：移交前只需接受 F-001 的 P2（DEPT-003 产物预签名下载缺少显式负向测试；代码路径已由同一 `requireVisible` 保护，`ArtifactContentApiTest` 已覆盖其他工作空间 404）。本评审不修改 TASK-0043 状态。

**B1b 可以在 B4 done 后启动**：B4 没有修改 V9/可见性谓词；内容入口已全部收敛到 `requireAccessible`，`FileRepository.lockAccessible` 也组合 `WorkspaceJdbc.findAccessible`，满足 B1b 对“所有内容入口先判定空间”的前置。**B5 可以启动**：B2/B3 已完成，B4 只影响知识库演示数据，不阻塞部门管理页与空间 UI。B5 仍需自行覆盖 UI 的 403/404 文案与知识库固定明示文案。

## 独立验证（干净树，HEAD `3e25194`）

| 命令/检查                                                                                                                                                                                              | 退出码      | 结果                                                                                                                                                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git status --short`                                                                                                                                                                                   | 0           | 空；HEAD `3e25194`                                                                                                                                                |
| `git show --stat 1b7905c 953d59b`                                                                                                                                                                      | 0           | `1b7905c` 15 个代码/测试文件（1475 insertions）；`953d59b` 只有 EV-0318 日志/JSON、state/task                                                                     |
| `git diff --name-status 14a8abd..1b7905c`                                                                                                                                                              | 0           | 仅 `chat/**`、`file/**`、`research/**`、`run/**` 与 4 个测试类；无 V9/迁移、workspace 包、`config/**`、前端、PRODUCT/AGENTS/contracts/威胁模型                    |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=ContentScopeApiTest,ChatScopeApiTest,ConversationApiTest,RunDetailApiTest -Dsurefire.failIfNoSpecifiedTests=false test`                                        | 0           | **16 tests / 0 failures / 0 errors / 0 skipped**（Content 4、Chat 3、Conversation 5、RunDetail 4），surefire 19:42–19:43                                          |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=FileLifecycleApiTest,KnowledgeApiTest,ArtifactApiTest,ArtifactContentApiTest,ResearchTaskApiTest,ApprovalApiTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0           | **36 tests / 0 failures / 0 errors / 0 skipped**（FileLifecycle 6、Knowledge 5、Artifact 5、ArtifactContent 9、ResearchTask 7、Approval 4），surefire 19:47–19:48 |
| `./mvnw -q spotless:check`                                                                                                                                                                             | 0           | 通过                                                                                                                                                              |
| `grep -rn "WORKSPACE_VISIBLE\|created_by = :userId\|w\\.kind = 'TEAM'" apps/api/src/main/java \| grep -v /workspace/`                                                                                  | 1（无匹配） | workspace 包外无这些旧谓词                                                                                                                                        |
| `riccon contract validate --strict --project .`                                                                                                                                                        | 0           | 0 errors, 0 warnings                                                                                                                                              |
| `riccon state validate --project .`                                                                                                                                                                    | 0           | 0 errors, 0 warnings                                                                                                                                              |
| `git merge-base --is-ancestor 1b7905c HEAD` / `953d59b HEAD`                                                                                                                                           | 0 / 0       | 代码与证据提交均为 HEAD 祖先                                                                                                                                      |
| `git diff --check 14a8abd..1b7905c`                                                                                                                                                                    | 0           | 无空白错误                                                                                                                                                        |
| `sha256(EV-0318 log)`                                                                                                                                                                                  | —           | `7d1f336cb6…33dc`，与 EV JSON 和 461158 bytes 一致                                                                                                                |

**EV-0318 审计**：`commit=1b7905c5…`、`dirty=false`、`exit_code=0`，运行窗口 19:28:24–19:37:24，命令 `./mvnw -q -T1C verify`。按 EV 窗口统计 fresh surefire 为 **576 tests / 0 failures / 0 errors / 0 skipped**：API 265、worker fresh 262、platform-common 33、platform-domain 4、ArchUnit 12。当前 `target` 中另有 07:51 的 `AgentStreamTimingProbeTest` 1 个残留报告（B3 已记录的已知现象），不计入 EV 的 576；577 − 1 = 576 与任务 brief 一致。API 覆盖率 **行 90.77% / 分支 73.10%**（`jacoco.csv` 生成于 19:37:08），worker 87.89/70.77，platform-common 98.55/98.15，platform-domain 94.44/无分支；均高于合同 80/70。

**未重复 full verify**：按 brief 只跑了两组受影响的定向测试并审计 EV-0318。EV-0318 是后端 `verify`，不包含合同 `commands.test` 的 `pnpm -w test`；B4 无前端改动，任务级证据可接受，但 M03 模块评审必须用完整合同命令的全量 EV，不能拿 EV-0318 替代模块 gate。

## 内容入口清单对账

| 对象/入口                                      | 实现点                                                                                                | 空间判定                                                                                     | 结论                            |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | ------------------------------- |
| 会话列表 `GET /workspaces/{id}/conversations`  | `ConversationService.list:123`                                                                        | 先 `requireAccessible(workspaceId)`，再按 workspace 查询                                     | 通过                            |
| 会话创建 `POST /workspaces/{id}/conversations` | `ConversationService.create:150`                                                                      | 先 `requireAccessible(workspaceId)`                                                          | 通过                            |
| 会话详情 `GET /conversations/{id}`             | `ConversationService.get:189` → `requireAccessible:297`                                               | 按会话 `workspaceId`                                                                         | 通过                            |
| 消息列表 `GET /conversations/{id}/messages`    | `ConversationService.listMessages:276` → `requireAccessible:297`                                      | 按会话 `workspaceId`                                                                         | 通过                            |
| 发送消息 `POST /conversations/{id}/messages`   | `ChatSubmissionService.prepare:143-151`                                                               | 先按会话 ID 读 `workspaceId`，再 `requireAccessible`，再锁会话                               | 通过                            |
| 停止生成 `POST /conversations/{id}/stop`       | `ChatService.stop:411`                                                                                | 先 `requireAccessible`，再按会话/运行/创建人判定                                             | 通过                            |
| 会话改名/删除                                  | `ConversationService.update:205` / `delete:236`                                                       | 先 `requireAccessible`，再 `requireCreator:315`                                              | 通过；可见非创建人 403          |
| 文件列表/上传                                  | `FileService.list:239` / `upload:151`                                                                 | 先 `requireAccessible(workspaceId)`；上传另校验会话归属                                      | 通过                            |
| 文件详情/下载/重试/删除                        | `FileService:259/332/275/352` → `requireAccessibleFile:402`                                           | 按文件 `workspaceId`；删除再校验上传者                                                       | 通过；可见非上传者 403          |
| 报告任务列表/创建                              | `ResearchTaskService.list:263` / `create:174`                                                         | 先 `requireAccessible(workspaceId)`；幂等重放再按既有任务空间判定 `:319`                     | 通过                            |
| 报告任务详情                                   | `ResearchTaskService.get:286`                                                                         | 按任务 `workspaceId`                                                                         | 通过                            |
| 产物列表/详情/版本/正文/下载                   | `ArtifactService.list:126` / `get/listVersions/getVersion/content/downloadUrl` → `requireVisible:304` | 按产物 `workspaceId`                                                                         | 通过（B4 未改，但已是统一入口） |
| 运行详情                                       | `RunDetailService.get:101` → `requireVisible:119-150`                                                 | CHAT 走会话空间；RESEARCH_REPORT 走任务空间；FILE_PARSE 走文件空间；KNOWLEDGE_INDEX 仅发起人 | 通过                            |
| 知识库登记源文件                               | `KnowledgeService.java:138` → `FileRepository.lockAccessible:88-93`                                   | 文件行锁后组合 `WorkspaceJdbc.findAccessible`                                                | 通过；未复制可见性 SQL          |

**旧谓词清理**：`FileJdbc` 不再有 `WORKSPACE_VISIBLE`，`byIdSql` 只按 `(tenant_id, file_id)` 查；`ConversationJdbc` 的 `findOwned/listOwned/countOwned/findOwnedForUpdate` 已删除，改为 `findById/findByIdForUpdate/countActive/listActive`，仅按 workspace 与 id 过滤；`RunDetailService` 的 CHAT 分支改为 `conversationService.requireAccessible`。`grep` 精确命令无匹配；唯一出现 `kind = 'TEAM'` 的位置是 `FileJdbc` 的中文注释（说明已删除的规则），不是 SQL。`DepartmentAdminJdbc:157` 的 `w.visibility = 'CUSTOM'` 是 B2 移出部门时清理 `workspace_member` 的写路径 SQL，不是内容可见性判定，不计入第二份谓词。

## 共享空间语义与 404/403 对账

- **共享空间可读**：`ConversationService.list` 从 `countOwned/listOwned` 改为 `countActive/listActive`，只按 `workspace_id` 过滤，不再按 `created_by`；`ContentScopeApiTest.deptSpaceContent_visibleToDepartmentMembers` 断言部门成员看到管理员创建的会话、文件、报告、产物和三类运行详情；`ConversationApiTest.listIsPagedAndSpaceScoped` 断言 admin/member 在 DEPT 空间各看到 4 条会话（含对方创建的会话），并保留分页 4/2/2 断言。
- **共享空间可追加消息**：`ChatSubmissionService` 去掉 `findOwnedForUpdate`，改为 `findById → requireAccessible → findByIdForUpdate`；`ChatScopeApiTest.sendMessage_memberContributesToSharedConversation` 断言成员可在管理员会话中发送，`agent_run.user_id` 是实际发送人、消息 2 条、标题生成、`message.sent` 审计 actor 是成员，且 Worker 只被调用一次。
- **个人空间仅 owner**：个人空间仍走 `PRIVATE` 谓词；`ContentScopeApiTest` 断言管理员/其他部门成员访问成员个人空间的会话和文件均为 404。
- **跨部门/跨租户 404**：列表、详情、消息、文件、报告、产物、运行详情的越权均为 `RICCON-TENANT-404`；`ContentScopeApiTest` 覆盖部门 B 与未分配账号，跨租户用第二个 fixture 覆盖。`ChatScopeApiTest.sendMessage_outsideSpace_returnsNotFound` 还断言越权发送不调用 Worker、不产生 run。
- **对象级 403**：共享空间成员修改/删除他人会话返回 403 `RICCON-AUTH-403`，非上传者删除文件返回 403；两者都先经过空间判定，所以“不可见”仍是 404，“可见但无对象级权限”才是 403。`ContentScopeApiTest.sharedSpaceMutations_stayCreatorOrUploaderOnly` 与 `ConversationApiTest` 均有断言。该变化符合 ADR-0018 §3.2（标题修改：创建人；文件删除：上传者）和 §7.3（可见但缺能力 403、不可见 404）；OpenAPI 的 conversation PATCH/DELETE、file DELETE 均声明 403/404，未出现契约缺声明。
- **停止生成**：`ChatService.canStop` 只允许会话创建人或该 run 发起人；`ChatScopeApiTest.stopGeneration_creatorOrRunInitiatorOnly` 覆盖非成员 404、成员停管理员 run 404、成员停自己 run 202、创建人停成员 run 202。无停止权限统一 404，避免泄露 run 归属；OpenAPI stop 同时声明 403/404，当前实现选择 404 不构成契约冲突。
- **跨部门/跨租户仍 404，响应不泄露存在性**：403 只发生在对象已可见的共享空间；所有不可见路径均由 `requireAccessible` 在对象级检查前抛出 404。`ContentScopeApiTest.outsideScopeContent_returnsNotFound` 断言 404 与稳定错误码；没有把“他人会话”继续当作 404（M02 语义已在 B4 更新为共享空间可读）。

## 并发与 TOCTOU

- **发送**：`ChatSubmissionService.prepare` 先无锁读取会话和 `workspaceId` 并判定访问，再 `findByIdForUpdate` 锁定会话行；锁后才做 `clientMessageId` 幂等、`maxSeq`、消息/run/审计插入。这样不会在持有会话锁时做空间查询，也不会把访问判定放到锁后遗漏。若成员在“判定后、锁定前”被移出部门，最多当前这一个已在途请求完成，下一次请求即 404；与 ADR §3.3“下一次请求生效”一致，不是跨请求缓存。
- **会话删除/改名**：`requireCreator` 在 `requireAccessible` 之后、`updateTitle/softDelete` 之前；`created_by` 不可变，服务层判定后 SQL 不再重复 owner 过滤，不引入 TOCTOU。`findByIdForUpdate` 与软删除同一行锁，避免发送与删除交错。
- **附件**：`requireUsableFiles` 以文件 ID 调用 `fileRepository.findById`（只按租户、状态、未删除），随后要求 `conversationId` 与 `workspaceId` 同时匹配会话；会话空间已判定，因此附件不能来自其他空间。`ChatSubmissionService` 不依赖旧 `findAccessible` 的 owner 判定。
- **文件锁与知识库**：`FileRepository.lockAccessible` 先 `FileJdbc.findByIdForUpdate` 锁文件，再调用 `WorkspaceJdbc.findAccessible` 过滤，未复制 SQL；知识库登记继续只在源文件可访问时成功。`FileService.requireAccessibleFileForUpdate` 采用“读对象 → 空间判定 → 加锁重读”，在锁前有短暂窗口；与发送相同，属于单请求在途语义，下一次请求即 404。
- **移出部门**：所有内容路径只依赖 `WorkspaceJdbc` 的当前数据库关系，无跨请求缓存；B3 的 `WorkspaceScopeTest` 已覆盖移出部门后空间访问立即失效。B4 未新增内容级移出用例（F-004），但代码路径不会缓存旧部门/旧成员。

## RunDetail 四类运行

- **CHAT**：按会话所属空间判定，共享空间成员可读；不再按 `run.userId` 或会话创建人限制。`RunDetailApiTest.get_sharedDeptChatRun_visibleToDepartmentMembers` 断言同部门成员 200、其他部门 404；旧 `get_invisibleRun_returns404` 仍用个人空间断言跨租户/他人 404，没有被弱化。
- **RESEARCH_REPORT**：按 `research_task.workspace_id` 判定；`RunDetailApiTest.get_researchRun_followsWorkspaceVisibility` 仍以个人空间为夹具，成员 404 不回归；`ContentScopeApiTest` 的团队空间报告 run 对部门成员 200。
- **FILE_PARSE**：按来源文件 `workspace_id` 判定；团队文件 run 对部门成员 200，个人空间他人/跨租户 404。文件软删除后 `FileJdbc.findById` 返回空，运行详情统一 404；这是 fail-closed 行为，不泄露文件是否存在，也与文件本身已不可读一致，未发现越权读取路径。
- **KNOWLEDGE_INDEX**：知识库在 M03 是租户级共享，但运行详情含模型调用明细；仅发起人可读。该规则比“租户级知识库”更严格，不会扩大可见面，未发现泄露；当前没有专门的新用例，属于 M02 既有策略，B4 未改变。
- **跨租户/跨部门**：`RunJdbc.findDetail` 先按租户读取，随后按对象关系判定；`ContentScopeApiTest` 覆盖 CHAT/RESEARCH_REPORT 跨部门 404，`ArtifactContentApiTest` 覆盖产物正文的其他工作空间 404。

## 测试与覆盖率

- **定向**：本轮独立运行 16 + 36 tests，均 exit 0、0 failures/errors/skipped；`ContentScopeApiTest` 4 个用例分别覆盖共享内容、越权 404、对象级 403 和 workspace 包外 SQL 守卫；`ChatScopeApiTest` 3 个用例覆盖发送/越权发送/停止；`ConversationApiTest.listIsPagedAndSpaceScoped` 保留分页断言并新增成员互见与 403；`RunDetailApiTest` 新增共享空间 CHAT run 用例，旧个人空间 404 用例保留。
- **EV-0318**：fresh 576 tests / 0 skipped；API 265（B3 的 257 + B4 新增 8），worker 262（不含 07:51 残留）、common 33、domain 4、ArchUnit 12；与 B4 新增测试数吻合。覆盖率 API 90.77/73.10，高于门槛。
- **假绿检查**：未发现 `@Disabled`、`assumeTrue`、`@Ignore`、空断言或只做快照的用例；`ContentScopeApiTest`/`ChatScopeApiTest` 使用真实 MySQL/对象存储/HTTP，没有 mock 持久化；`MockWorkerServer` 只是 JDK `HttpServer` 替代 Worker HTTP，不替代数据库、空间判定或 API 鉴权。`ConversationApiTest` 的旧 owner-only 断言是被 M03 语义替换，不是删除；`RunDetailApiTest` 的旧 404 断言仍在。
- **守卫评估**：`ContentScopeApiTest.noVisibilitySqlOutsideWorkspacePackage` 不是空断言，它遍历 `apps/api/src/main/java` 并排除 `/workspace/`，断言 offenders 为空；但它是字符串/正则启发式，只匹配 `WORKSPACE_VISIBLE`、`w.kind = 'TEAM'`、`w.owner_user_id = :userId`，无法覆盖其他别名、`visibility` 组合或资源 SQL（F-003）。作为当前回归守卫有效，作为长期 ArchUnit 替代不足。

## 边界、状态与编码规范

- **写入范围**：`git diff --name-status 14a8abd..1b7905c` 只有 11 个生产文件（chat/file/research/run）和 4 个测试文件；未修改 V9/迁移、`workspace/**`、`config/**`、前端、PRODUCT/AGENTS/contracts/威胁模型；没有新增 V10 或改动 V9 校验和。
- **状态**：`TASK-0043.status=review`、`end_commit=1b7905c`；`EV-0318.commit=1b7905c` 且为 HEAD 祖先；`TASK-0043.evidence=[EV-0318]`；工作区干净；`state validate` 0 errors/0 warnings。未发现 builder 自标 done。
- **规范**：新增/修改的 public 方法有中文 Javadoc；复杂 SQL、共享空间、对象级权限、知识库边界有中文说明；`spotless:check` 通过；无 TODO/FIXME、注释掉的代码、`System.out` 或密钥。仍有三处旧语义注释（F-002），不影响生产行为但应清理。

## Findings 与承接

### F-001（P2，accepted）：B4 作用域测试未显式覆盖 DEPT-003 的产物预签名下载 404

- **位置**：`apps/api/src/test/java/com/riccon/api/ContentScopeApiTest.java:140-141` 只对产物做 `GET /artifacts/{id}` 与列表 404 断言；`:94` 只断言成员下载 200。`apps/api/src/test/java/com/riccon/api/ArtifactApiTest.java:160` 的下载地址用例没有跨工作空间负向断言。
- **观察**：威胁模型 `DEPT-003` 要求“他部门员工持有文件/产物 ID 请求下载或预签名 → 统一 404；不签发 URL”。B4 已覆盖文件下载 URL 的 404，但产物 `/versions/{version}/download-url` 的越权负向路径没有显式用例。`ArtifactService.downloadUrl:263` 与正文一样先走 `requireVisible`，`ArtifactContentApiTest` 已覆盖其他工作空间正文 404，因此这是测试覆盖缺口，不是生产代码绕过。
- **期望**：在 `ContentScopeApiTest` 增加 outsideScope 对 `GET /artifacts/{id}/versions/1/download-url`（以及可选 `/content`）的 404 + 不返回 URL 断言；或在 M03-V 的 DEPT-003 中补。
- **承接**：**M03-V**（DEPT-003）；B4 可按书面理由接受该 P2，不阻塞 done。若 Lead 不接受 P2 承接，则在 B4 完成前补测试。

### F-002（P3，accepted）：三处生产注释仍保留 M02 创建人私有语义

- **位置**：`apps/api/src/main/java/com/riccon/api/chat/domain/ConversationRecord.java:10`（“M02 只允许创建人读取与写入”）；`.../chat/controller/ConversationController.java:55`（“跨租户或他人会话统一 404”）；`.../chat/service/ChatSubmissionService.java:130`（`@throws NotFoundException 会话不存在或不属于当前员工`）。
- **观察**：M03 已允许共享空间成员读取他人创建的会话并追加消息，上述注释与代码/ADR 冲突，可能误导后续维护者。
- **期望**：分别改为“会话属于工作空间；共享空间成员可读写，标题/删除限创建人，个人空间仅 owner”等表述。
- **承接**：**B4 清理/M03-V 文档收尾**；不影响行为，不阻塞。

### F-003（P3，accepted）：workspace 包外可见性 SQL 守卫是启发式

- **位置**：`apps/api/src/test/java/com/riccon/api/ContentScopeApiTest.java:201-235`，尤其 `:229-231`。
- **观察**：守卫遍历 Java 源码并排除 `/workspace/`，但只匹配 `WORKSPACE_VISIBLE`、`w.kind = 'TEAM'`、`w.owner_user_id = :userId`；使用其他别名（如 `ws.kind`）、用 `visibility IN (...)` 组合、或在 XML/资源 SQL 中复制可见性规则不会被发现。它不是空断言，也不只是 grep 注释；当前旧谓词确实已清零。
- **期望**：M03-V/架构收口增加 ArchUnit 规则（禁止 `workspace` 包外的可见性常量/SQL 片段或特定方法名），或至少把匹配扩展到别名与 `owner_user_id` 的常见写法。
- **承接**：**M03-V / M03 评审包**。

### F-004（P3，accepted）：成员移出部门后的内容级 404 只由空间谓词测试间接覆盖

- **位置**：`WorkspaceScopeTest.java:232-250` 覆盖移出部门后空间访问失效；B4 的 `ContentScopeApiTest`/`ChatScopeApiTest` 没有在移出后直接请求会话/文件/报告/run 详情。
- **观察**：B4 的所有内容入口都先走同一个 `requireAccessible`，因此谓词级测试已证明行为；但 AC-07 的“下一次请求即无法访问该空间及其对象”在内容层面缺少直接回归，未来若有人在某个入口绕过空间判定，现有测试可能只发现空间列表失败而漏掉内容对象。
- **期望**：M03-V 的 `E2E-M03-02`/`IT-DEPT-004` 或新增集成用例：成员在部门空间创建对象后被移出，逐个请求会话详情/消息/文件/下载/报告/run detail 均 404。
- **承接**：**M03-V**（AC-07 内容段）。

### F-005（P3，accepted）：审批列表与决策仍为租户级，未随报告任务工作空间收窄

- **位置**：`ApprovalService.list:123-140`、`ApprovalService.decide:145-...`；`ApprovalJdbc` 的 `LIST_FILTER` 只有 `tenant_id`；`ApprovalResponse` 含 `researchTaskId`、`toolName`、`paramsPreview`。
- **观察**：任何拥有 `report.approve` 的普通员工（MEMBER 默认拥有，见 `RoleDefinitions`）都能列出并决定本租户所有部门的报告任务审批。ADR-0018 §3.2 的说明是“审批仍由 `report.approve` + 租户内可见性约束”，因此这是 M02 既有租户级策略的保留，不是 B4 引入的越权；但如果 M03-AC-04 的“报告列表/详情”被解释为包含审批项，需要在 M03 验收中明确该例外。
- **期望**：M03-V/M03 评审包明确记录该租户级例外；若产品要求部门隔离，则另立任务给审批列表/决策补 `workspaceId` 关联与 404。
- **承接**：**M03-V / M03 评审包**；B4 不阻塞。

## 剩余风险与未验证

- **未重复 full verify**：只审计 EV-0318 并跑 16 + 36 个定向测试；未在本轮重跑完整 `./mvnw verify`（EV-0318 已覆盖，576 tests/0 skipped，exit 0、dirty=false）。
- **未跑模块 gate**：E2E、axe/a11y、trivy、k6、前端测试、V9 迁移/回滚、`riccon gate check --module M03` 均未在本任务执行；B4 无 UI/迁移/依赖变更，这些属于 B5/M03-V/模块 gate。M03 仍为 `building`，现在运行 gate check 应失败。
- **未验证**：F-001 产物下载负向用例、F-004 内容级移出用例只做了代码/测试审计，没有独立新增测试；F-003 的守卫未做变异测试；并发交错只做代码路径分析，没有多线程失败注入（B3 F-001 仍在 M03-V）。
- **契约漂移（非 B4）**：`contracts/openapi.yaml` 仍声明 `/api/v1/research-tasks/{id}/cancel`、`/retry`、`/events`，而 `ResearchTaskService`/`ResearchTaskController` 当前只有 create/list/get；B4 未改 contracts，这是 M02 遗留的契约—实现缺口，建议 M03 评审包确认或由 Architect 收口。
- **审批租户级**：F-005 的例外需在 M03 评审包明确；不影响 B4 的写范围与本次 verdict。
- **文档影响**：B4 行为与 ADR-0018 §3.1/§3.2/§7.3 和 OpenAPI 的 403/404 声明一致；除 F-002 的旧注释外没有需要同步的架构章节。

## 是否可进入 B1b/B5

- **B4 可 done**：6 项验收均有代码/测试支撑；定向 16 + 36 tests、EV-0318 fresh 576 tests/0 skipped、API 覆盖率 90.77/73.10 已核对；无 P0/P1。Lead 在 B4 closeout 时接受 F-001 的 P2 并记录 F-002..F-005 的承接即可；本评审不改任务状态。
- **B1b 可启动**（B4 done 后）：B4 未改 V9/workspace 谓词，所有内容入口已收敛到 `requireAccessible`，`FileRepository.lockAccessible` 组合 `WorkspaceJdbc.findAccessible`；B1b 的 V9 收紧、迁移 seed→V9 测试与回滚演练按 ADR §6.2 独立执行。
- **B5 可启动**：B2/B3 已完成，B4 只影响知识库演示数据；B5 的部门管理页/空间可见性/成员 UI 与知识库固定文案可开始。M03-V 随后负责 DEPT-001..010、E2E、a11y 与模块评审。
