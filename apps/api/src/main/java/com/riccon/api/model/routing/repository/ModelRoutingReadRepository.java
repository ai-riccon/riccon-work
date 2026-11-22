package com.riccon.api.model.routing.repository;

import com.riccon.api.model.routing.domain.RoutingChain;
import com.riccon.api.model.routing.persistence.ModelRoutingQueryJdbc;
import com.riccon.platform.domain.model.ModelPurpose;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 模型路由链读仓储（生效读模型）。
 *
 * <p>读路径刻意不做缓存：每次请求都重新查询全局路由表，保证管理员保存链后下一次模型请求使用新链
 * （ADR-0021 §4.3 AC-01）。本仓储只被平台管理服务调用，员工端不直接读路由表。
 */
@Repository
public class ModelRoutingReadRepository {

    /** 路由链只读访问。 */
    private final ModelRoutingQueryJdbc queryJdbc;

    /**
     * 创建路由链读仓储。
     *
     * @param queryJdbc 路由链只读访问
     */
    public ModelRoutingReadRepository(ModelRoutingQueryJdbc queryJdbc) {
        this.queryJdbc = Objects.requireNonNull(queryJdbc, "queryJdbc must not be null");
    }

    /**
     * 读取两条路由链。
     *
     * @return 按 purpose 升序的链
     */
    public List<RoutingChain> findAll() {
        return queryJdbc.findAll();
    }

    /**
     * 按用途读取路由链。
     *
     * @param purpose 用途
     * @return 链；未种子化时返回空
     */
    public Optional<RoutingChain> find(ModelPurpose purpose) {
        return queryJdbc.find(purpose);
    }
}
