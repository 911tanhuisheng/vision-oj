package com.sandbox.service.impl.java;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.sandbox.config.DockerHelper;
import com.sandbox.model.ExecuteCodeResponse;
import com.sandbox.model.ExecuteMessage;
import com.sandbox.model.JudgeInfo;
import com.sandbox.sandbox.template.AbstractTemplateCodeSandbox;
import com.sandbox.sandbox.template.SandboxContext;
import com.sandbox.sandbox.template.SandboxError;
import com.sandbox.sandbox.template.SandboxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Docker 版 Java 沙箱（继承模板方法实现）。
 */
@Slf4j
@Service
public class DockerJavaCodeSandbox extends AbstractTemplateCodeSandbox {

    private static final String WORK_DIR = "/app";
    private static final String MAIN_FILE = "Main.java";
    private static final long BYTES_PER_KB = 1024L;
    private static final int MAX_STDOUT_BYTES = 64 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;

    /**
     * 镜像探测缓存：同一进程生命周期内只做一次 inspect/pull。
     */
    private static volatile boolean IMAGE_READY = false;

    /**
     * 每次 executeCode 在线程内复用容器，compile/run/cleanup 三步共享。
     */
    private final ThreadLocal<String> containerHolder = new ThreadLocal<>();
    private final ThreadLocal<Long> memoryPeakHolder = ThreadLocal.withInitial(() -> 0L);

    @Override
    protected void compile(SandboxContext ctx) {
        DockerClient client = DockerHelper.client();
        String containerId = null;
        try {
            String image = sandboxProperties.getDocker().getJavaImage();
            ensureImage(client, image);
            // 方案 B：不再 copy 文件到容器。直接把宿主机工作目录 bind 挂载到 /app（rw），
            // 同时保持根文件系统只读。
            containerId = createSandboxContainer(client, image, ctx.workDir.getAbsolutePath());

            ExecuteMessage compileMsg = execInContainer(
                    client,
                    containerId,
                    new String[]{"javac", "-encoding", "UTF-8", "-d", "/tmp", WORK_DIR + "/" + MAIN_FILE},
                    "",
                    sandboxProperties.getExecution().getDockerCompileTimeoutMs()
            );
            if (compileMsg.getExitValue() == null || compileMsg.getExitValue() != 0) {
                throw new SandboxException(SandboxError.COMPILE_ERROR, "编译失败", firstDetail(compileMsg));
            }

            containerHolder.set(containerId);
            containerId = null; // ownership moved to holder
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            log.error("Docker 编译阶段异常", e);
            throw new SandboxException(SandboxError.SYSTEM_ERROR, "沙箱内部异常", e.getMessage());
        } finally {
            if (containerId != null) {
                try {
                    client.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception ignore) {
                }
            }
            try {
                client.close();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected void runAll(SandboxContext ctx) {
        String containerId = containerHolder.get();
        if (StrUtil.isBlank(containerId)) {
            throw new SandboxException(SandboxError.SYSTEM_ERROR, "沙箱内部异常", "missing container id");
        }

        DockerClient client = DockerHelper.client();
        long peakMemoryBytes = 0L;
        try {
            for (int i = 0; i < ctx.inputList.size(); i++) {
                String input = ctx.inputList.get(i);
                // 稳定方案：写入宿主机工作目录，容器通过重定向读取，避免 docker exec stdin 通道偶发阻塞
                String inputFileName = ".input_" + i + ".txt";
                FileUtil.writeString(input == null ? "" : input, new File(ctx.workDir, inputFileName), StandardCharsets.UTF_8);
                String inputPathInContainer = WORK_DIR + "/" + inputFileName;

                // 不将用户输入拼接到 shell 命令，避免命令注入；统一通过 stdin 文件传参。
                String runShell = "java -Xmx128m -Dfile.encoding=UTF-8 -cp /tmp Main < " + inputPathInContainer;

                ExecuteMessage runMsg = execInContainer(
                        client,
                        containerId,
                        new String[]{"sh", "-lc", runShell},
                        "",
                        sandboxProperties.getExecution().getDockerRunTimeoutMs()
                );
                long currentMemoryBytes = readContainerMemoryPeakBytes(client, containerId);
                runMsg.setMemory(bytesToKb(currentMemoryBytes));
                peakMemoryBytes = Math.max(peakMemoryBytes, currentMemoryBytes);
                if (runMsg.getTime() != null) {
                    ctx.maxTimeMs = Math.max(ctx.maxTimeMs, runMsg.getTime());
                }
                if (runMsg.getExitValue() == null || runMsg.getExitValue() != 0) {
                    boolean tle = runMsg.getExitValue() != null && runMsg.getExitValue() == -1;
                    throw new SandboxException(
                            tle ? SandboxError.TIME_LIMIT : SandboxError.RUNTIME_ERROR,
                            tle ? "时间限制超出" : "运行失败",
                            firstDetail(runMsg)
                    );
                }
                ctx.outputList.add(StrUtil.nullToEmpty(runMsg.getMessage()).trim());
            }
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            log.error("Docker 运行阶段异常", e);
            throw new SandboxException(SandboxError.SYSTEM_ERROR, "沙箱内部异常", e.getMessage());
        } finally {
            memoryPeakHolder.set(peakMemoryBytes);
            try {
                client.close();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected void cleanup(SandboxContext ctx) {
        String containerId = containerHolder.get();
        DockerClient client = DockerHelper.client();
        try {
            if (StrUtil.isNotBlank(containerId)) {
                try {
                    client.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception ignore) {
                }
            }
        } finally {
            containerHolder.remove();
            memoryPeakHolder.remove();
            try {
                client.close();
            } catch (Exception ignore) {
            }
            super.cleanup(ctx);
        }
    }

    /**
     * 覆盖模板的成功响应，回填内存峰值。
     */
    @Override
    protected ExecuteCodeResponse buildSuccessResponse(SandboxContext ctx) {
        ExecuteCodeResponse resp = super.buildSuccessResponse(ctx);
        if (resp.getJudgeInfo() == null) {
            resp.setJudgeInfo(new JudgeInfo());
        }
        resp.getJudgeInfo().setMemory(bytesToKb(memoryPeakHolder.get()));
        return resp;
    }

    /**
     * 在通用失败响应基础上补充 Docker 内存统计。
     */
    @Override
    protected ExecuteCodeResponse buildFailResponse(SandboxContext ctx, SandboxException e) {
        ExecuteCodeResponse resp = super.buildFailResponse(ctx, e);
        if (resp.getJudgeInfo() == null) {
            resp.setJudgeInfo(new JudgeInfo());
        }
        resp.getJudgeInfo().setMemory(bytesToKb(memoryPeakHolder.get()));
        return resp;
    }

    private static String createSandboxContainer(DockerClient client, String image, String hostWorkDir) {
        Map<String, String> tmpFs = new HashMap<>();
        tmpFs.put("/tmp", "rw,noexec,nosuid,size=64m");
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withMemory(256L * 1024 * 1024)
                .withMemorySwap(256L * 1024 * 1024)
                .withPidsLimit(64L)
                .withCpuPeriod(100_000L)
                .withCpuQuota(100_000L)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of(
                        "no-new-privileges:true",
                        "apparmor=docker-default"
                ))
                .withBinds(new Bind(hostWorkDir, new Volume(WORK_DIR), AccessMode.rw))
                .withTmpFs(tmpFs);

        CreateContainerResponse resp = client.createContainerCmd(image)
                .withName("sandbox_" + UUID.randomUUID().toString().replace("-", ""))
                .withHostConfig(hostConfig)
                .withWorkingDir("/app")
                .withUser("1000:1000")
                .withCmd("sh", "-c", "sleep 3600")
                .exec();
        client.startContainerCmd(resp.getId()).exec();
        return resp.getId();
    }

    private static void ensureImage(DockerClient client, String image) throws InterruptedException {
        if (IMAGE_READY) {
            return;
        }
        synchronized (DockerJavaCodeSandbox.class) {
            if (IMAGE_READY) {
                return;
            }
            try {
                client.inspectImageCmd(image).exec();
                IMAGE_READY = true;
                return;
            } catch (NotFoundException ignore) {
                // 继续 pull
            }
            client.pullImageCmd(image).start().awaitCompletion();
            IMAGE_READY = true;
        }
    }

    private static ExecuteMessage execInContainer(
            DockerClient client,
            String containerId,
            String[] cmd,
            String stdin,
            long timeoutMs
    ) throws Exception {
        ExecCreateCmdResponse execCreate = client.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withAttachStdin(StrUtil.isNotBlank(stdin))
                .withTty(false)
                .withCmd(cmd)
                .exec();

        ExecuteMessage message = new ExecuteMessage();
        long start = System.currentTimeMillis();
        CappedBuffer out = new CappedBuffer(MAX_STDOUT_BYTES, "stdout");
        CappedBuffer err = new CappedBuffer(MAX_STDERR_BYTES, "stderr");
        // 为了兼容 Scanner.nextLine()/nextInt() 等常见读法，确保输入以换行结束
        String normalizedInput = stdin == null ? "" : stdin;
        if (StrUtil.isNotBlank(normalizedInput) && !normalizedInput.endsWith("\n")) {
            normalizedInput = normalizedInput + "\n";
        }
        ByteArrayInputStream in = new ByteArrayInputStream(normalizedInput.getBytes(StandardCharsets.UTF_8));
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                try {
                    if (frame != null && frame.getPayload() != null) {
                        switch (frame.getStreamType()) {
                            case STDERR -> err.append(frame.getPayload());
                            default -> out.append(frame.getPayload());
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        };
        try {
            var startCmd = client.execStartCmd(execCreate.getId())
                    .withDetach(false)
                    .withTty(false);
            if (StrUtil.isNotBlank(stdin)) {
                startCmd.withStdIn(in);
            }
            boolean completed = startCmd.exec(callback).awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);

            message.setMessage(out.toText());
            message.setErrorMessage(err.toText());

            if (!completed) {
                message.setExitValue(-1);
                message.setErrorMessage("时间限制超出");
                message.setTime(System.currentTimeMillis() - start);
                message.setMemory(0L);
                // 超时时直接杀容器，防止残留进程继续占资源
                try {
                    client.killContainerCmd(containerId).exec();
                } catch (Exception ignore) {
                }
                return message;
            }
        } finally {
            try {
                callback.close();
            } catch (Exception ignore) {
            }
        }

        InspectExecResponse inspect = client.inspectExecCmd(execCreate.getId()).exec();
        Long exitCodeLong = inspect.getExitCodeLong();
        message.setExitValue(exitCodeLong == null ? -1 : exitCodeLong.intValue());
        message.setTime(System.currentTimeMillis() - start);
        message.setMemory(0L);
        return message;
    }

    private static long readContainerMemoryPeakBytes(DockerClient client, String containerId) {
        AtomicLong peakBytes = new AtomicLong(0L);
        ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Statistics statistics) {
                if (statistics == null || statistics.getMemoryStats() == null) {
                    return;
                }
                Long maxUsage = statistics.getMemoryStats().getMaxUsage();
                Long usage = statistics.getMemoryStats().getUsage();
                long candidate = maxUsage != null ? maxUsage : (usage == null ? 0L : usage);
                peakBytes.set(Math.max(peakBytes.get(), candidate));
            }
        };

        try {
            client.statsCmd(containerId).withNoStream(true).exec(callback).awaitCompletion(3, TimeUnit.SECONDS);
            return peakBytes.get();
        } catch (Exception e) {
            log.warn("读取容器内存统计失败, containerId={}", containerId, e);
            return 0L;
        } finally {
            try {
                callback.close();
            } catch (Exception ignore) {
            }
        }
    }

    protected static String firstDetail(ExecuteMessage m) {
        if (m == null) {
            return "";
        }
        if (StrUtil.isNotBlank(m.getErrorMessage())) {
            return m.getErrorMessage().trim();
        }
        return StrUtil.nullToEmpty(m.getMessage()).trim();
    }

    /**
     * 向上取整转 KB，避免小于 1KB 的内存显示为 0。
     */
    private static long bytesToKb(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return (bytes + BYTES_PER_KB - 1) / BYTES_PER_KB;
    }

    /**
     * 限制输出采集大小，避免用户程序无限打印导致宿主 JVM OOM。
     */
    private static final class CappedBuffer {
        private final int maxBytes;
        private final String streamName;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private boolean truncated = false;
        private long receivedBytes = 0L;

        private CappedBuffer(int maxBytes, String streamName) {
            this.maxBytes = maxBytes;
            this.streamName = streamName;
        }

        private synchronized void append(byte[] payload) {
            if (payload == null || payload.length == 0) {
                return;
            }
            receivedBytes += payload.length;
            int remaining = maxBytes - delegate.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            if (payload.length <= remaining) {
                delegate.write(payload, 0, payload.length);
                return;
            }
            delegate.write(payload, 0, remaining);
            truncated = true;
        }

        private synchronized String toText() {
            String text = delegate.toString(StandardCharsets.UTF_8);
            if (!truncated) {
                return text;
            }
            return text + System.lineSeparator()
                    + "[sandbox] " + streamName + " 已截断：最大 " + maxBytes
                    + " bytes，实际接收约 " + receivedBytes + " bytes";
        }
    }
}
