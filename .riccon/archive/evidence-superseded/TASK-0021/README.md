# TASK-0021 被替代证据（superseded evidence）

本目录保存 TASK-0021 早期录制的 dirty 证据，仅作历史留档，**不得**用于 gate 或评审的 `dod_evidence`。

| 证据    | 命令                                       | 退出码 | 问题                                                                                 | 替代证据 |
| ------- | ------------------------------------------ | ------ | ------------------------------------------------------------------------------------ | -------- |
| EV-0191 | `./mvnw -q -T1C verify`                    | 0      | `dirty=true`：录制期间 TASK-0022 正在同一工作区写 `apps/worker/**` 未提交文件        | EV-0207  |
| EV-0192 | `git diff --check && git log -1 --oneline` | 0      | 同上，`dirty=true`                                                                   | EV-0208  |
| EV-0205 | `./mvnw -q -T1C verify`                    | 0      | `dirty=true`：录制期间 TASK-0023 正在同一工作区写 `apps/web/**`、`e2e/**` 未提交文件 | EV-0207  |
| EV-0206 | `git diff --check && git log -1 --oneline` | 0      | 同上，`dirty=true`                                                                   | EV-0208  |

替代证据在 commit `436f851` 的干净 detach worktree 中录制（dirty=false，退出码 0），命令与上表完全相同：

- EV-0207：`./mvnw -q -T1C verify`
- EV-0208：`git diff --check && git log -1 --oneline`

四条旧记录已从 `.riccon/tasks/TASK-0021.json` 的 `evidence` 引用中移除，避免 strict gate 因 `dirty=true` 失败。

归档时间：2026-11-18（Asia/Shanghai）
