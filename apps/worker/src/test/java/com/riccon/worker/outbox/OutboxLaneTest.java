package com.riccon.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.worker.run.domain.OutboxLane;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox 通道与事件类型映射（ADR-0023 第 7.3 节）。
 *
 * <p>通道映射必须封闭且无重叠：同一事件出现在两个通道会导致两个轮询器争抢，同一事件无人登记
 * 会永久停在 PENDING。测试放在 {@code com.riccon.worker.outbox}，避免与并行任务拥有的
 * worker run 测试目录写范围冲突。
 */
class OutboxLaneTest {

    /** 配置码解析大小写不敏感，未知码返回空。 */
    @Test
    @DisplayName("通道码解析大小写不敏感且未知码为空")
    void fromCode_isCaseInsensitiveAndRejectsUnknown() {
        assertThat(OutboxLane.fromCode("default")).contains(OutboxLane.DEFAULT);
        assertThat(OutboxLane.fromCode(" HEAVY ")).contains(OutboxLane.HEAVY);
        assertThat(OutboxLane.fromCode("Background")).isEmpty();
        assertThat(OutboxLane.fromCode(null)).isEmpty();
    }

    /** 默认通道只消费 chat/report 事件，不吞文件解析/索引/连接器同步。 */
    @Test
    @DisplayName("默认通道保持 chat/report 顺序语义")
    void defaultLane_containsOnlyChatAndReportEvents() {
        assertThat(OutboxLane.DEFAULT.eventTypes())
                .containsExactly(
                        EventTypes.AGENT_RUN_REQUESTED, EventTypes.AGENT_RUN_RESUME, EventTypes.AGENT_RUN_CANCEL)
                .doesNotContain(
                        EventTypes.FILE_PARSE_REQUESTED,
                        EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                        EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED);
    }

    /** 重通道包含三类长任务，供 outbox 双通道领取。 */
    @Test
    @DisplayName("重通道包含解析/索引/连接器同步")
    void heavyLane_containsLongRunningEvents() {
        assertThat(OutboxLane.HEAVY.eventTypes())
                .containsExactly(
                        EventTypes.FILE_PARSE_REQUESTED,
                        EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                        EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED);
    }

    /** 全部事件恰好登记一次，避免启动后事件无人领取或双领取。 */
    @Test
    @DisplayName("事件类型在通道间不重不漏")
    void allEventTypes_areCoveredExactlyOnce() {
        List<String> all = OutboxLane.allEventTypes();
        assertThat(all)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(List.of(
                        EventTypes.AGENT_RUN_REQUESTED,
                        EventTypes.AGENT_RUN_RESUME,
                        EventTypes.AGENT_RUN_CANCEL,
                        EventTypes.FILE_PARSE_REQUESTED,
                        EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                        EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED));
    }
}
