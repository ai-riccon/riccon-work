package com.riccon.worker.model.repository;

import com.riccon.worker.model.persistence.GatewaySwitchJdbc;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 模型网关运行时开关仓储。
 *
 * <p>每次请求读取一次；不缓存，满足“配置变更下一次请求生效”（ADR-0021 §4.3/§10.5）。
 */
@Component
public class GatewaySwitchRepository {

    /** 开关访问。 */
    private final GatewaySwitchJdbc switchJdbc;

    /**
     * 创建开关仓储。
     *
     * @param switchJdbc 开关访问
     */
    public GatewaySwitchRepository(GatewaySwitchJdbc switchJdbc) {
        this.switchJdbc = Objects.requireNonNull(switchJdbc, "switchJdbc must not be null");
    }

    /**
     * 读取开关键值快照。
     *
     * @return 键值映射
     */
    public Map<String, String> currentValues() {
        return switchJdbc.findAll();
    }
}
