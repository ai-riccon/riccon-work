package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.chat.domain.ChatEvent;
import com.riccon.worker.chat.service.ChatEventFactory;
import com.riccon.worker.persistence.UuidBinary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** SSE 事件字段、引用截断与失败码校验。 */
class ChatEventFactoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    private static final String TENANT = "tenant-a";

    private static final UUID RUN = UuidBinary.newV7();

    private static final UUID MESSAGE = UuidBinary.newV7();

    private static final String TRACE = "trace-1";

    /** 开始事件包含契约字段。 */
    @Test
    void started_containsRequiredFields() {
        ChatEvent event = factory().started(TENANT, RUN, MESSAGE, TRACE, UUID.randomUUID(), "fake-model");

        assertThat(event.eventType()).isEqualTo("riccon.chat.started.v1");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo("2026-11-17T10:00:00Z");
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.runId()).isEqualTo(RUN);
        assertThat(event.messageId()).isEqualTo(MESSAGE);
        assertThat(event.data()).isInstanceOf(ChatEvent.Started.class);
    }

    /** 引用事件截断 snippet 并映射定位。 */
    @Test
    void citations_mapsAndTruncates() {
        RetrievedChunk chunk = new RetrievedChunk(
                1,
                CitationSourceType.KNOWLEDGE,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocatorType.PAGE,
                3,
                null,
                null,
                null,
                "标题",
                "很长".repeat(400),
                0.8);

        ChatEvent event = factory().citations(TENANT, RUN, MESSAGE, TRACE, List.of(chunk));

        assertThat(event.eventType()).isEqualTo("riccon.chat.citation.v1");
        ChatEvent.CitationData data = (ChatEvent.CitationData) event.data();
        assertThat(data.citations()).hasSize(1);
        assertThat(data.citations().get(0).snippet()).hasSizeLessThanOrEqualTo(500);
        assertThat(data.citations().get(0).title()).isEqualTo("标题");
        assertThat(data.citations().get(0).pageNo()).isEqualTo(3);
    }

    /** 无引用时不发空引用事件。 */
    @Test
    void citations_empty_returnsNull() {
        assertThat(factory().citations(TENANT, RUN, MESSAGE, TRACE, List.of())).isNull();
    }

    /** 无标题时使用稳定占位。 */
    @Test
    void citations_nullTitle_usesFallback() {
        RetrievedChunk chunk = new RetrievedChunk(
                1,
                CitationSourceType.FILE,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                2,
                " ",
                "内容",
                1.0);

        ChatEvent event = factory().citations(TENANT, RUN, MESSAGE, TRACE, List.of(chunk));

        ChatEvent.CitationData data = (ChatEvent.CitationData) event.data();
        assertThat(data.citations().get(0).title()).isEqualTo("未命名来源");
    }

    /** 失败事件拒绝非法错误码。 */
    @Test
    void failed_invalidCode_throws() {
        assertThatThrownBy(() -> new ChatEvent.Failed("bad", "x", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatEvent.Delta("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatEvent.CitationData(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /** 事件可序列化为 JSON 且日期为 ISO 字符串。 */
    @Test
    void event_serializesIsoTimestamp() {
        ChatEvent event = factory().delta(TENANT, RUN, MESSAGE, TRACE, "hi");
        String json = new ObjectMapper().writeValueAsString(event);

        assertThat(json)
                .contains("\"eventType\":\"riccon.chat.delta.v1\"")
                .contains("\"occurredAt\":\"2026-11-17T10:00:00Z\"")
                .contains("\"delta\":\"hi\"");
    }

    /** 构造工厂。 */
    private static ChatEventFactory factory() {
        return new ChatEventFactory(CLOCK);
    }
}
