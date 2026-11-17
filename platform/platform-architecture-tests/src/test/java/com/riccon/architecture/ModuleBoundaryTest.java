package com.riccon.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块边界、技术栈隔离与 MVC 分层规则的机器校验。
 *
 * <p>这些规则是 M01 的验收证据：{@code apps/api} 与 {@code platform} 不得依赖 agentscope；agentscope 只能出现在
 * {@code apps/worker}。规则失败时优先修改依赖方向，不要放宽规则。
 */
class ModuleBoundaryTest {

    /** 只导入生产类，避免测试辅助类污染边界判断。 */
    private static final JavaClasses PROJECT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.riccon");

    /** 防止类路径变化导致规则在空集合上“假绿”。 */
    @Test
    void projectClassesAreLoaded() {
        assertThat(PROJECT_CLASSES)
                .extracting(JavaClass::getName)
                .contains(
                        "com.riccon.api.ApiApplication",
                        "com.riccon.worker.WorkerApplication",
                        "com.riccon.platform.common.tenant.TenantContext");
    }

    /** agentscope 只能出现在 worker，api 与 platform 必须保持框架无关。 */
    @Test
    void agentscopeIsOnlyAllowedInWorker() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.riccon..")
                .and()
                .resideOutsideOfPackage("com.riccon.worker..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.agentscope..")
                .because("AgentScope 是 worker 的运行时细节，不能泄漏到同步 API 或共享内核");
        rule.check(PROJECT_CLASSES);
    }

    /** api 与 worker 是并列部署物，不能互相编译依赖。 */
    @Test
    void deployablesDoNotDependOnEachOther() {
        ArchRule apiMustNotUseWorker = noClasses()
                .that()
                .resideInAPackage("com.riccon.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.riccon.worker..")
                .because("api 与 worker 只能通过契约通信，不能直接共享实现");
        ArchRule workerMustNotUseApi = noClasses()
                .that()
                .resideInAPackage("com.riccon.worker..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.riccon.api..")
                .because("worker 不得反向依赖同步 API 实现");

        apiMustNotUseWorker.check(PROJECT_CLASSES);
        workerMustNotUseApi.check(PROJECT_CLASSES);
    }

    /** platform 是被依赖方，不能反向依赖任何部署物，也不能依赖 Web 框架。 */
    @Test
    void platformStaysIndependent() {
        ArchRule platformMustNotUseApps = noClasses()
                .that()
                .resideInAPackage("com.riccon.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.riccon.api..", "com.riccon.worker..")
                .because("platform 是共享内核，依赖方向只能由 apps 指向 platform");
        ArchRule platformCommonMustNotUseWeb = noClasses()
                .that()
                .resideInAPackage("com.riccon.platform.common..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.servlet..")
                .because("platform-common 必须保持无框架，才能在 api、worker 与未来连接器中复用");

        platformMustNotUseApps.check(PROJECT_CLASSES);
        platformCommonMustNotUseWeb.check(PROJECT_CLASSES);
    }

    /** MVC 分层依赖必须单向：controller -> service -> repository。 */
    @Test
    void mvcLayeringIsOneWay() {
        ArchRule rule = layeredArchitecture()
                .consideringAllDependencies()
                // M01 还没有 controller/service/repository 实现；允许空层，类出现后规则立即生效。
                .withOptionalLayers(true)
                .layer("Controller")
                .definedBy("..controller..")
                .layer("Service")
                .definedBy("..service..")
                .layer("Repository")
                .definedBy("..repository..")
                .whereLayer("Controller")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Service")
                .mayOnlyBeAccessedByLayers("Controller")
                .whereLayer("Repository")
                .mayOnlyBeAccessedByLayers("Service")
                .because("MVC 保持小：依赖只能从外层指向内层，禁止 controller 被 service 反向调用");
        rule.check(PROJECT_CLASSES);
    }

    /** @Configuration 类必须位于 config 包，避免配置散落在业务包中。 */
    @Test
    void configurationClassesLiveInConfigPackage() {
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith(Configuration.class)
                .should()
                .resideInAPackage("..config..")
                .because("配置与业务实现分离，便于按部署物审查安全基线");
        rule.check(PROJECT_CLASSES);
    }

    /** Controller 必须使用 @RestController 并位于 controller 包，避免出现两套 Web 风格。 */
    @Test
    void controllersFollowNamingAndLocation() {
        ArchRule rule = classes()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .beAnnotatedWith(RestController.class)
                .andShould()
                .resideInAPackage("..controller..")
                .allowEmptyShould(true)
                .because("HTTP 入口统一为 @RestController + controller 包，便于后续统一鉴权与审计");
        rule.check(PROJECT_CLASSES);
    }
}
