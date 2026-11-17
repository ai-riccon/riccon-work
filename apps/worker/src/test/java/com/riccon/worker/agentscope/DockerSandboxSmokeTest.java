package com.riccon.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 冒烟 2：AgentScope Docker 沙箱。
 *
 * <p>agentscope-core 2.0.5 不含沙箱实现，官方 Docker 沙箱位于同版本 {@code agentscope-harness}
 * （{@link io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox}）。本测试在真实容器内执行命令、
 * 校验 stdout 与退出码，并确认 {@code close()} 会删除容器。
 *
 * <p>Docker 守护进程或本地镜像缺失时测试直接失败，不跳过；测试不会隐式联网拉取镜像。
 * 默认优先使用与合同 JDK 匹配的镜像；若本地没有该镜像，则回退到合同测试栈必然拉取的
 * {@code mysql:8.4.13}，避免 CI 额外联网。两种镜像都只用于执行 POSIX shell 命令。
 * 可通过 {@code -Driccon.sandbox.image=<镜像>} 显式指定。
 */
class DockerSandboxSmokeTest {

    /** 与合同 JDK 版本一致的本地镜像，优先使用。 */
    private static final String JDK_IMAGE = "eclipse-temurin:21.0.13_9-jdk-jammy";

    /** 合同测试栈（deploy/compose/docker-compose.test.yml）保证存在的回退镜像。 */
    private static final String COMPOSE_IMAGE = "mysql:8.4.13";

    private static final String IMAGE_PROPERTY = "riccon.sandbox.image";
    private static final String WORKSPACE_ROOT = "/workspace";

    /** 容器内执行成功命令时返回 stdout 与退出码 0，非零退出码通过异常暴露。 */
    @Test
    @Timeout(240)
    void dockerSandbox_executesCommandAndRemovesContainer() throws Exception {
        String image = assertDockerPrerequisites();

        DockerSandboxClient client = new DockerSandboxClient();
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot(WORKSPACE_ROOT);
        Sandbox sandbox = client.create(
                workspaceSpec,
                new NoopSnapshotSpec(),
                new DockerSandboxClientOptions().image(image).network("none"));
        DockerSandboxState sandboxState = (DockerSandboxState) sandbox.getState();

        try {
            sandbox.start();
            assertThat(sandbox.isRunning()).isTrue();

            ExecResult result = sandbox.exec(RuntimeContext.empty(), "printf 'riccon-sandbox-ok\\n'; pwd", 15);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("riccon-sandbox-ok").contains(WORKSPACE_ROOT);

            assertThatThrownBy(() -> sandbox.exec(RuntimeContext.empty(), "exit 7", 15))
                    .isInstanceOfSatisfying(
                            SandboxException.ExecException.class,
                            exception -> assertThat(exception.getExitCode()).isEqualTo(7));
        } finally {
            sandbox.close();
        }

        assertThat(sandboxState.getContainerId()).isNotBlank();
        ProcessResult inspect = runCommand("docker", "inspect", sandboxState.getContainerId());
        assertThat(inspect.exitCode())
                .as("容器应在 close() 后被删除，但 docker inspect 仍成功：%s", inspect.output())
                .isNotZero();
    }

    /**
     * 校验 Docker 守护进程并选择本地已有的镜像；缺失时给出可执行的失败原因而不是跳过测试。
     *
     * @return 可用的沙箱镜像名
     */
    private static String assertDockerPrerequisites() throws IOException, InterruptedException {
        ProcessResult docker = runCommand("docker", "version", "--format", "{{.Server.Version}}");
        assertThat(docker.exitCode())
                .as("Docker 守护进程不可用，沙箱冒烟必须失败：%s", docker.output())
                .isZero();

        String override = System.getProperty(IMAGE_PROPERTY);
        List<String> candidates =
                override == null || override.isBlank() ? List.of(JDK_IMAGE, COMPOSE_IMAGE) : List.of(override);
        for (String image : candidates) {
            if (runCommand("docker", "image", "inspect", image).exitCode() == 0) {
                return image;
            }
        }
        throw new AssertionError(
                "本地缺少沙箱镜像，候选为 " + candidates + "；请先 docker pull，或用 -D" + IMAGE_PROPERTY + " 指定（测试不会隐式联网拉取）");
    }

    /**
     * 执行一次本地命令并收集合并输出，带固定超时以避免测试挂死。
     *
     * @param command 命令及参数
     * @return 退出码与输出
     */
    private static ProcessResult runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = exited ? process.exitValue() : -1;
        return new ProcessResult(exitCode, output);
    }

    /** 本地命令的退出码与输出。 */
    private record ProcessResult(int exitCode, String output) {}
}
