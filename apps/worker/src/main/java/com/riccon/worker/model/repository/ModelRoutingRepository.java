package com.riccon.worker.model.repository;

import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.model.domain.RoutingChainEntry;
import com.riccon.worker.model.persistence.ModelRoutingJdbc;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 模型路由链只读仓储（全局表）。
 *
 * <p>只读；链的写入由 API 平台管理员端点负责（B3）。Worker 每次请求读取新链，
 * 不引入跨请求缓存。
 */
@Component
public class ModelRoutingRepository {

    /** 路由链访问。 */
    private final ModelRoutingJdbc routingJdbc;

    /**
     * 创建路由链仓储。
     *
     * @param routingJdbc 路由链访问
     */
    public ModelRoutingRepository(ModelRoutingJdbc routingJdbc) {
        this.routingJdbc = Objects.requireNonNull(routingJdbc, "routingJdbc must not be null");
    }

    /**
     * 读取可路由链条目。
     *
     * @param purpose 用途
     * @return 按 position 排序的链
     */
    public List<RoutingChainEntry> entries(ModelPurpose purpose) {
        return routingJdbc.findEntries(purpose);
    }

    /**
     * 判断链是否被管理员显式保存过。
     *
     * @param purpose 用途
     * @return 已保存返回 true
     */
    public boolean configured(ModelPurpose purpose) {
        return routingJdbc.isChainConfigured(purpose);
    }
}
