package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 发布图节点（契约 {@code WorkflowNode} 的 Worker 只读副本）。
 *
 * <p>节点 key 由客户端生成，格式 {@code [A-Za-z0-9_-]{1,64}}；运行入口重新校验，避免损坏快照
 * 写入节点表或用于租约推进（ADR-0024 §4.1）。
 *
 * @param id 节点 key
 * @param type 节点类型
 * @param name 节点名快照
 * @param config 节点配置
 */
public record WorkflowGraphNode(String id, WorkflowNodeType type, String name, WorkflowGraphNodeConfig config) {

    /** 节点 key 允许的字符与长度。 */
    public static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 节点名最大长度（与 {@code workflow_node_run.node_name} 列宽一致）。 */
    public static final int MAX_NAME_LENGTH = 200;

    /** 校验必填字段、ID 格式与名称长度。 */
    public WorkflowGraphNode {
        Objects.requireNonNull(id, "node id must not be null");
        Objects.requireNonNull(type, "node type must not be null");
        Objects.requireNonNull(name, "node name must not be null");
        Objects.requireNonNull(config, "node config must not be null");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("node id is invalid");
        }
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("node name length is invalid");
        }
    }
}
