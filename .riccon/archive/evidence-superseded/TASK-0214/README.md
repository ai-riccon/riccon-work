# TASK-0214 被并发会话重复记录的证据

- EV-0985：lead 会话在 `lead-b12b` namespace 独立复跑 `Connector*Test,KnowledgeDownloadApiTest,KnowledgeAclApiTest`，exit 0；但同一时间另一活跃会话已在 `eea093e` 记录 EV-0984（`builder-b12b` namespace）并将任务转 review。
- 权威证据：EV-0984（已提交、已绑定 TASK-0214 与 state）。EV-0985 仅作并行复跑留档，不用于 gate/评审的 `dod_evidence`。
- 处理：本会话已将 TASK-0214/state 恢复到 `eea093e`，不覆盖另一会话的状态；保留本副本避免静默删除。
