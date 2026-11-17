package com.riccon.worker.chat.service;

import io.agentscope.core.ReActAgent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 活跃对话流注册表。
 *
 * <p>API 取消上游请求时通过 runId 找到 Agent 并中断；注册表只在 Worker 进程内有效，
 * 不跨实例共享。未注册的运行（如等待审批的报告任务）由数据库状态直接取消。
 */
@Component
public class RunCancellationRegistry {

    /** runId 到取消句柄。 */
    private final ConcurrentHashMap<UUID, RunCancellation> active = new ConcurrentHashMap<>();

    /**
     * 注册运行；同一 runId 只允许一个活跃流。
     *
     * @param runId 运行 ID
     * @param agent Agent
     * @return 取消句柄
     * @throws IllegalStateException 已有活跃流
     */
    public RunCancellation register(UUID runId, ReActAgent agent) {
        RunCancellation cancellation = new RunCancellation(agent);
        RunCancellation existing = active.putIfAbsent(runId, cancellation);
        if (existing != null) {
            throw new IllegalStateException("run already has an active stream");
        }
        return cancellation;
    }

    /**
     * 取消运行；不存在时返回 false。
     *
     * @param runId 运行 ID
     * @param reason 原因
     * @return 是否找到并取消
     */
    public boolean cancel(UUID runId, String reason) {
        RunCancellation cancellation = active.get(runId);
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel(reason);
        return true;
    }

    /**
     * 注销运行。
     *
     * @param runId 运行 ID
     * @param cancellation 句柄
     */
    public void unregister(UUID runId, RunCancellation cancellation) {
        active.remove(runId, cancellation);
    }

    /**
     * 查询活跃句柄，供测试与诊断。
     *
     * @param runId 运行 ID
     * @return 句柄
     */
    public Optional<RunCancellation> find(UUID runId) {
        return Optional.ofNullable(active.get(runId));
    }

    /** 单个运行的取消状态；第一个原因生效。 */
    public static final class RunCancellation {

        /** Agent。 */
        private final ReActAgent agent;

        /** 取消原因。 */
        private final AtomicReference<String> reason = new AtomicReference<>();

        /**
         * 创建句柄。
         *
         * @param agent Agent
         */
        private RunCancellation(ReActAgent agent) {
            this.agent = Objects.requireNonNull(agent, "agent must not be null");
        }

        /**
         * 请求取消并中断 Agent；重复调用幂等。
         *
         * @param value 原因
         */
        public void cancel(String value) {
            String normalized = value == null || value.isBlank() ? "USER_CANCELLED" : value;
            if (reason.compareAndSet(null, normalized)) {
                agent.interrupt();
            }
        }

        /**
         * 是否已取消。
         *
         * @return 是否取消
         */
        public boolean cancelled() {
            return reason.get() != null;
        }

        /**
         * 返回取消原因；未取消时为 null。
         *
         * @return 原因
         */
        public String reason() {
            return reason.get();
        }
    }
}
