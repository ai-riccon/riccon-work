package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.chat.domain.ChatEvent;
import com.riccon.worker.persistence.UuidBinary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SSE 事件构造器边界。 */
class ChatEventValidationTest {

    /** 必填字段缺失失败。 */
    @Test
    void event_missingFields_fails() {
        UUID id = UuidBinary.newV7();
        assertThatThrownBy(
                        () -> new ChatEvent(null, "t", 1, "now", "tenant", id, id, "trace", new ChatEvent.Delta("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new ChatEvent(id, "t", 1, "now", "tenant", null, id, "trace", new ChatEvent.Delta("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new ChatEvent(id, "t", 1, "now", "tenant", id, null, "trace", new ChatEvent.Delta("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatEvent(id, "t", 1, "now", "tenant", id, id, null, new ChatEvent.Delta("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatEvent(id, "t", 1, "now", "tenant", id, id, "trace", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 引用必填字段、序号与片段长度校验。 */
    @Test
    void citation_invalid_fails() {
        assertThatThrownBy(() -> citation(0, "标题", "片段")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> citation(1, null, "片段")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> citation(1, "标题", "x".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ChatEvent.CitationData(List.of(citation(1, "标题", "片段"))).citations())
                .hasSize(1);
    }

    /** 构造引用。 */
    private static ChatEvent.Citation citation(int ordinal, String title, String snippet) {
        return new ChatEvent.Citation(
                ordinal,
                CitationSourceType.KNOWLEDGE,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                1,
                title,
                snippet,
                0.5);
    }
}
