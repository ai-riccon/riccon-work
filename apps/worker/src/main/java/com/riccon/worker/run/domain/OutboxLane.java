package com.riccon.worker.run.domain;

import com.riccon.platform.domain.contract.EventTypes;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Outbox 处理通道（ADR-0023 第 7.3 节）。
 *
 * <p>重通道（{@link #HEAVY}）承载文件解析、知识库索引与连接器同步等长任务，默认并发 2；默认通道
 * （{@link #DEFAULT}）保持 chat/report 事件顺序处理。领取 SQL 以事件类型过滤，通道之间互不阻塞；
 * 运行类别与租户校验仍由 {@code OutboxPoller} 负责。
 *
 * <p>通道与事件类型的映射是封闭的：新增事件必须在某一通道登记，否则启动时会告警且事件无法被领取。
 */
public enum OutboxLane {

    /** 默认通道：Agent 运行、审批恢复与取消；单并发保持同一租户的运行顺序。 */
    DEFAULT(
            "default",
            List.of(EventTypes.AGENT_RUN_REQUESTED, EventTypes.AGENT_RUN_RESUME, EventTypes.AGENT_RUN_CANCEL)),

    /** 重通道：文件解析、知识库索引与连接器同步；默认并发 2，支持租约续期。 */
    HEAVY(
            "heavy",
            List.of(
                    EventTypes.FILE_PARSE_REQUESTED,
                    EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                    EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED));

    /** 配置中使用的通道码（小写）。 */
    private final String code;

    /** 本通道消费的事件类型。 */
    private final List<String> eventTypes;

    /**
     * 创建通道定义。
     *
     * @param code 配置码
     * @param eventTypes 事件类型
     */
    OutboxLane(String code, List<String> eventTypes) {
        this.code = code;
        this.eventTypes = List.copyOf(eventTypes);
    }

    /**
     * 返回配置码。
     *
     * @return 小写通道码
     */
    public String code() {
        return code;
    }

    /**
     * 返回本通道消费的事件类型。
     *
     * @return 不可变事件类型列表
     */
    public List<String> eventTypes() {
        return eventTypes;
    }

    /**
     * 按配置码解析通道，大小写不敏感。
     *
     * @param code 配置码
     * @return 通道；未知时为空
     */
    public static Optional<OutboxLane> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.strip().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(lane -> lane.code.equals(normalized))
                .findFirst();
    }

    /**
     * 返回所有已登记事件类型，用于启动自检“是否存在无人消费的事件”。
     *
     * @return 事件类型列表
     */
    public static List<String> allEventTypes() {
        return Arrays.stream(values()).flatMap(lane -> lane.eventTypes.stream()).toList();
    }
}
