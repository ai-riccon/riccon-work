package com.riccon.platform.common.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 当前线程的租户上下文。
 *
 * <p>M01 仅提供基于 {@link ThreadLocal} 的最小实现，供同步 HTTP 与 JDBC 场景使用。M02 接入异步与响应式链路时，
 * 必须在边界处显式传递租户，不得依赖线程继承或 Reactor 的隐式上下文；扩展方案见 ADR-0003。
 *
 * <p>使用约定：
 *
 * <ul>
 *   <li>请求入口（Filter/Interceptor）负责打开 {@link #open(TenantId)} 作用域，并在 finally 中关闭；
 *   <li>任何跨线程提交任务前必须捕获 {@link #get()} 的当前值，并在目标线程内重新打开作用域；
 *   <li>禁止在未设置租户时访问业务数据，{@link #require()} 会直接失败。
 * </ul>
 */
public final class TenantContext {

    /** 每个线程独立的租户槽位。 */
    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    /** 工具类禁止实例化。 */
    private TenantContext() {}

    /**
     * 在当前线程设置租户。
     *
     * @param tenantId 租户标识，不能为 {@code null}
     */
    public static void set(TenantId tenantId) {
        CURRENT.set(Objects.requireNonNull(tenantId, "tenantId must not be null"));
    }

    /**
     * 读取当前线程的租户。
     *
     * @return 已设置时返回租户，否则返回空
     */
    public static Optional<TenantId> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 读取当前线程的租户，未设置时立即失败。
     *
     * @return 当前租户
     * @throws IllegalStateException 当前线程没有租户上下文
     */
    public static TenantId require() {
        TenantId tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("tenant context is not set");
        }
        return tenantId;
    }

    /** 清理当前线程的租户，避免线程池复用导致租户串号。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 打开一个可自动关闭的租户作用域。
     *
     * <p>关闭时恢复进入前的租户，支持嵌套调用；推荐写法：
     *
     * <pre>{@code
     * try (TenantScope ignored = TenantContext.open(tenantId)) {
     *   // 业务逻辑
     * }
     * }</pre>
     *
     * @param tenantId 本次作用域的租户
     * @return 关闭时恢复原租户的作用域
     */
    public static TenantScope open(TenantId tenantId) {
        return new TenantScope(tenantId);
    }

    /**
     * 在指定租户下执行有返回值的逻辑，并保证退出时恢复原上下文。
     *
     * @param tenantId 执行期间使用的租户
     * @param action 业务逻辑
     * @param <T> 返回值类型
     * @return 业务逻辑的返回值
     */
    public static <T> T callWithTenant(TenantId tenantId, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        try (TenantScope ignored = open(tenantId)) {
            return action.get();
        }
    }

    /**
     * 在指定租户下执行无返回值的逻辑，并保证退出时恢复原上下文。
     *
     * @param tenantId 执行期间使用的租户
     * @param action 业务逻辑
     */
    public static void runWithTenant(TenantId tenantId, Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        try (TenantScope ignored = open(tenantId)) {
            action.run();
        }
    }

    /** 租户作用域；关闭时恢复进入前的租户，而不是无条件清空。 */
    public static final class TenantScope implements AutoCloseable {

        /** 进入作用域前的租户，可能为空。 */
        private final TenantId previous;

        /** 作用域是否已关闭，避免重复关闭破坏外层上下文。 */
        private boolean closed;

        /**
         * 设置当前租户并记录原值。
         *
         * @param tenantId 本次作用域的租户
         */
        private TenantScope(TenantId tenantId) {
            this.previous = CURRENT.get();
            CURRENT.set(Objects.requireNonNull(tenantId, "tenantId must not be null"));
        }

        /** 恢复进入作用域前的租户。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
