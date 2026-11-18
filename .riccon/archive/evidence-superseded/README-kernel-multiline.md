# 多行 shell 证据归档

EV-0126/EV-0133/EV-0134（TASK-0011）与 EV-0178（TASK-0018）记录了多行 shell 脚本。
Riccon 证据日志头只保留 `# command:` 的首行，strict 校验会把多行 JSON 命令判为与日志头不一致。
这些记录已归档，不参与 gate；对应能力仍由 TASK-0011/TASK-0018 的其余单行证据与真实栈迁移验证覆盖。
