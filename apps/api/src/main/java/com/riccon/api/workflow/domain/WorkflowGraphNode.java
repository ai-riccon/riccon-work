package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 图节点（契约 {@code WorkflowNode}）。
 *
 * <p>节点 ID 由客户端生成，格式 {@code [A-Za-z0-9_-]{1,64}}，在流程内唯一；服务端不依赖数据库自增。
 * 节点类型是封闭枚举，未知类型在保存/发布/运行三处拒绝（ADR-0024 第 4.1 节，AC-24）。
 *
 * @param id 节点 ID
 * @param type 节点类型
 * @param name 展示名，1–200 字符
 * @param config 节点配置；必填，可以为空对象
 * @param position 画布坐标；可空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphNode(
        String id, WorkflowNodeType type, String name, WorkflowGraphNodeConfig config, WorkflowGraphPosition position) {

    /** 节点与边 ID 允许的字符与长度，与契约 pattern 一致。 */
    public static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 节点名最大长度，与契约一致。 */
    public static final int MAX_NAME_LENGTH = 200;

    /** 校验必填字段、ID 格式与名称长度。 */
    public WorkflowGraphNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id must match " + ID_PATTERN.pattern());
        }
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name length must be between 1 and " + MAX_NAME_LENGTH);
        }
    }
}
