# TASK-0242 被替代证据

- EV-1042 / EV-1043：命令通过（exit 0），但录制时并行任务 TASK-0223 的未提交 `apps/api` 运行包改动仍在工作树中，`dirty=true`，不能用于 strict gate。
- EV-1046 / EV-1047：clean，但录制后按写入范围把 `config/routes.test.ts` 的新增断言迁到 `src/layouts/workspace/navigation.test.ts`（`config/routes.test.ts` 不在 TASK-0242 write_scope），测试树已变化，因此被最终证据替代。
- 权威证据：`.riccon/evidence/TASK-0242/EV-1048.json`（全量 web 测试，clean）与 `EV-1049.json`（typecheck + lint + production build，clean），均绑定最终提交。

本目录只保留审计副本。
