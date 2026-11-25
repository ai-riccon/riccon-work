package com.riccon.worker.workflow.heartbeat.domain;

import java.util.UUID;

/**
 * 运行执行租约句柄。
 *
 * <p>队列层在领取成功后先启动心跳，再把句柄交给执行器；执行器在节点边界检查
 * {@link #lost()} 与 {@link #cancelled()}，两者任一为真都必须停止写入结果。心跳续期失败、
 * 租约被其他实例重领或运行被取消时，句柄会停止续期并置位对应标志，避免双执行
 * （ADR-0024 §4.3/§4.6、TM-81）。
 */
public interface WorkflowLease extends AutoCloseable {

    /**
     * 运行 ID。
     *
     * @return 运行 ID
     */
    UUID runId();

    /**
     * 租户原始字符串（已通过校验）。
     *
     * @return 租户
     */
    String tenantId();

    /**
     * 租约是否已丢失。
     *
     * @return true 表示执行结果必须丢弃
     */
    boolean lost();

    /**
     * 运行是否已被取消。
     *
     * @return true 表示应尽快停止执行
     */
    boolean cancelled();

    /**
     * 是否已停止续期。
     *
     * @return true 表示句柄已关闭或失去租约
     */
    boolean stopped();

    /** 停止续期并释放调度任务；重复关闭无副作用。 */
    @Override
    void close();
}
