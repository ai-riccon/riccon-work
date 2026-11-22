package com.riccon.platform.domain.contract;

/**
 * 事件类型常量。
 *
 * <p>Outbox 事件由 API 写入、Worker 消费；SSE 事件由 Worker 产生、API 转发或由 API 根据运行状态派生。
 * 事件载荷 schema 位于 {@code contracts/events/}，新增事件必须同时新增 schema 与契约测试。
 */
public interface EventTypes {

    /** API → Worker：启动一次报告任务运行。 */
    String AGENT_RUN_REQUESTED = "riccon.agent.run.requested.v1";
    /** API → Worker：审批通过后恢复下一阶段。 */
    String AGENT_RUN_RESUME = "riccon.agent.run.resume.v1";
    /** API → Worker：取消运行。 */
    String AGENT_RUN_CANCEL = "riccon.agent.run.cancel.v1";
    /** API → Worker：解析已上传文件。 */
    String FILE_PARSE_REQUESTED = "riccon.file.parse.requested.v1";
    /** API → Worker：构建/重建知识库索引；payload 可选 {@code mode}（FULL/ACL_ONLY/DELETE）。 */
    String KNOWLEDGE_INDEX_REQUESTED = "riccon.knowledge.index.requested.v1";
    /** API → Worker：触发一次连接器同步（M05b；幂等键 {@code connector:{id}:sync:{syncId}}）。 */
    String KNOWLEDGE_CONNECTOR_SYNC_REQUESTED = "riccon.knowledge.connector.sync.requested.v1";

    /** Worker → API/浏览器：回答开始。 */
    String CHAT_STARTED = "riccon.chat.started.v1";
    /** Worker → API/浏览器：回答增量。 */
    String CHAT_DELTA = "riccon.chat.delta.v1";
    /** Worker → API/浏览器：引用列表。 */
    String CHAT_CITATION = "riccon.chat.citation.v1";
    /** Worker → API/浏览器：回答正常结束。 */
    String CHAT_COMPLETED = "riccon.chat.completed.v1";
    /** Worker → API/浏览器：回答失败。 */
    String CHAT_FAILED = "riccon.chat.failed.v1";
    /** Worker → API/浏览器：用户停止或客户端断开。 */
    String CHAT_STOPPED = "riccon.chat.stopped.v1";

    /** API → 浏览器：报告阶段变化。 */
    String RUN_STAGE = "riccon.run.stage.v1";
    /** API → 浏览器：需要审批。 */
    String RUN_APPROVAL_REQUIRED = "riccon.run.approval.required.v1";
    /** API → 浏览器：审批已决定。 */
    String RUN_APPROVAL_DECIDED = "riccon.run.approval.decided.v1";
    /** API → 浏览器：报告完成。 */
    String RUN_COMPLETED = "riccon.run.completed.v1";
    /** API → 浏览器：报告失败。 */
    String RUN_FAILED = "riccon.run.failed.v1";
    /** API → 浏览器：报告取消。 */
    String RUN_CANCELLED = "riccon.run.cancelled.v1";
}
