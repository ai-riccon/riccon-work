package com.riccon.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * API 合成事件测试。
 *
 * <p>确认 failed/stopped 事件能通过同一校验器，并携带正确的租户、运行、消息与 trace 绑定。
 */
class ApiChatEventFactoryTest {

    /** JSON 工厂。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定时钟。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    /** 校验器。 */
    private static final ChatEventValidator VALIDATOR = new ChatEventValidator();

    /** 租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-a");

    /** 运行。 */
    private static final UUID RUN_ID = UUID.randomUUID();

    /** 消息。 */
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    /** trace。 */
    private static final String TRACE = "trace-1";

    /** failed 事件合法。 */
    @Test
    void failedEventIsContractValid() {
        ApiChatEventFactory factory = new ApiChatEventFactory(MAPPER, CLOCK);
        ObjectNode event =
                factory.failed(TENANT.value(), RUN_ID, MESSAGE_ID, TRACE, "RICCON-CHAT-502", "回答流意外结束", true);

        ChatEventValidator.ValidatedEvent validated = VALIDATOR.validate(event, TENANT, RUN_ID, TRACE);
        assertThat(validated.eventType()).isEqualTo(EventTypes.CHAT_FAILED);
        assertThat(validated.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(validated.failedCode()).isEqualTo("RICCON-CHAT-502");
        assertThat(validated.retryable()).isTrue();
        assertThat(event.path("occurredAt").asText()).isEqualTo("2026-11-17T10:00:00Z");
    }

    /** stopped 事件合法。 */
    @Test
    void stoppedEventIsContractValid() {
        ApiChatEventFactory factory = new ApiChatEventFactory(MAPPER, CLOCK);
        ObjectNode event = factory.stopped(TENANT.value(), RUN_ID, MESSAGE_ID, TRACE, "USER_CANCELLED");

        ChatEventValidator.ValidatedEvent validated = VALIDATOR.validate(event, TENANT, RUN_ID, TRACE);
        assertThat(validated.eventType()).isEqualTo(EventTypes.CHAT_STOPPED);
        assertThat(validated.stopReason()).isEqualTo("USER_CANCELLED");
    }
}
