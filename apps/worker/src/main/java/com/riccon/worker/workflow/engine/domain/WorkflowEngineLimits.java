package com.riccon.worker.workflow.engine.domain;

/**
 * 串行引擎固定限制（ADR-0024 §3.4/§4.4）。
 *
 * <p>这些值是发布图结构与节点输出的协议常量，不是部署配置：单节点 {@code output_json}
 * 固定 256 KiB、引用单节点最多 20 条、摘要列固定 2000 字符。部署可调的上限（节点数、
 * 步数、节点/运行超时、运行结果总大小）由 {@code WorkflowRuntimeProperties} 提供。
 */
public final class WorkflowEngineLimits {

    /** 单流程节点数上限：50（ADR-0024 §4.1，与契约/API 硬上限一致）。 */
    public static final int MAX_GRAPH_NODES = 50;

    /** 单节点 {@code output_json} 上限：256 KiB。 */
    public static final int NODE_OUTPUT_MAX_BYTES = 256 * 1024;

    /** 单节点引用快照上限：20 条（与检索 topK 上限一致）。 */
    public static final int MAX_CITATIONS_PER_NODE = 20;

    /** 输入/输出摘要列宽：2000 字符。 */
    public static final int SUMMARY_MAX_LENGTH = 2000;

    /** 错误信息列宽：500 字符。 */
    public static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 单流程边数上限：100（ADR-0024 §4.1）。 */
    public static final int MAX_EDGES = 100;

    /** 检索候选上限的部署默认值（与 M05 Agent 检索默认一致）。 */
    public static final int DEFAULT_RETRIEVAL_CANDIDATES = 60;

    /** 检索候选上限的硬上界（M05 校验范围 1–200）。 */
    public static final int MAX_RETRIEVAL_CANDIDATES = 200;

    /** 工具类禁止实例化。 */
    private WorkflowEngineLimits() {}
}
