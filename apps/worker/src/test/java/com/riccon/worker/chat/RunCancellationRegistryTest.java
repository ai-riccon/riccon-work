package com.riccon.worker.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.riccon.worker.chat.service.RunCancellationRegistry;
import com.riccon.worker.persistence.UuidBinary;
import io.agentscope.core.ReActAgent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 活跃流取消注册表。 */
class RunCancellationRegistryTest {

    /** 注册、取消、重复取消与注销。 */
    @Test
    void registerCancelUnregister_works() {
        RunCancellationRegistry registry = new RunCancellationRegistry();
        ReActAgent agent = mock(ReActAgent.class);
        UUID runId = UuidBinary.newV7();

        RunCancellationRegistry.RunCancellation cancellation = registry.register(runId, agent);
        assertThat(registry.find(runId)).contains(cancellation);
        assertThat(cancellation.cancelled()).isFalse();
        assertThat(registry.cancel(runId, "USER_CANCELLED")).isTrue();
        assertThat(cancellation.cancelled()).isTrue();
        assertThat(cancellation.reason()).isEqualTo("USER_CANCELLED");
        assertThat(registry.cancel(runId, "PERMISSION_REVOKED")).isTrue();
        assertThat(cancellation.reason()).isEqualTo("USER_CANCELLED");
        registry.unregister(runId, cancellation);
        assertThat(registry.find(runId)).isEmpty();
    }

    /** 重复注册同一运行失败；空原因归一为 USER_CANCELLED。 */
    @Test
    void duplicateAndBlankReason_handled() {
        RunCancellationRegistry registry = new RunCancellationRegistry();
        ReActAgent agent = mock(ReActAgent.class);
        UUID runId = UuidBinary.newV7();
        RunCancellationRegistry.RunCancellation cancellation = registry.register(runId, agent);

        assertThatThrownBy(() -> registry.register(runId, agent)).isInstanceOf(IllegalStateException.class);
        cancellation.cancel(" ");
        assertThat(cancellation.reason()).isEqualTo("USER_CANCELLED");
        assertThat(registry.cancel(UUID.randomUUID(), "USER_CANCELLED")).isFalse();
    }
}
