package com.riccon.worker.workflow.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.config.WorkflowRuntimeConfiguration;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.engine.service.WorkflowEngine;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.service.WorkflowNodeDispatcher;
import com.riccon.worker.workflow.node.service.WorkflowResultAssembler;
import com.riccon.worker.workflow.queue.domain.WorkflowRunExecutor;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * 串行引擎 Bean 装配（非 test profile）。
 *
 * <p>{@link WorkflowEngine} 在 test profile 下不注册（避免共享集成测试上下文启动真实调度槽位），
 * 本测试用最小上下文验证默认 profile 下引擎作为 {@link WorkflowRunExecutor} 唯一 Bean 可装配，
 * 且分派器能收集节点执行器——这是 T06a 调度器“executor 存在后自动启动槽位”的装配前提。
 */
class WorkflowEngineWiringTest {

    /** 最小上下文运行器。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    WorkflowRuntimeConfiguration.class, WorkflowEngine.class, WorkflowNodeDispatcher.class)
            .withBean(WorkflowEngineRepository.class, () -> mock(WorkflowEngineRepository.class))
            .withBean(WorkflowResultAssembler.class, () -> mock(WorkflowResultAssembler.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(WorkflowNodeExecutor.class, WorkflowEngineWiringTest::modelExecutor);

    /** 默认 profile 下引擎 Bean 可装配并暴露运行执行端口。 */
    @Test
    @DisplayName("默认 profile 装配 WorkflowRunExecutor")
    void defaultProfile_wiresEngineAsRunExecutor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkflowRunExecutor.class);
            assertThat(context).hasSingleBean(WorkflowEngine.class);
            assertThat(context).hasSingleBean(WorkflowRuntimeProperties.class);
            assertThat(context.getBean(WorkflowRunExecutor.class)).isInstanceOf(WorkflowEngine.class);
        });
    }

    /**
     * 构造模型节点桩执行器。
     *
     * @return 执行器
     */
    private static WorkflowNodeExecutor modelExecutor() {
        return new WorkflowNodeExecutor() {
            @Override
            public WorkflowNodeType nodeType() {
                return WorkflowNodeType.MODEL;
            }

            @Override
            public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
                return WorkflowNodeResult.of("in", "out", Map.of(), List.of());
            }
        };
    }
}
