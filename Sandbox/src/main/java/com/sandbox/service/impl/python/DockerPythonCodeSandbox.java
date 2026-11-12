package com.sandbox.service.impl.python;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
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
import java.util.Collections;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Docker 版 Python 沙箱（继承模板方法实现）。
 */
@Slf4j
@Service
public class DockerPythonCodeSandbox extends AbstractTemplateCodeSandbox {

    private static final String WORK_DIR = "/app";
    private static final String IMAGE_DIR = "/input-images";
    private static final String MAIN_FILE = "main.py";
    private static final long COMPILE_TIMEOUT_MS = 10_000L;
    private static final long RUN_TIMEOUT_MS = 30_000L;
    private static final long BYTES_PER_KB = 1024L;
    private static final int MAX_STDOUT_BYTES = 64 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 30 * 1024 * 1024;

    private static volatile boolean IMAGE_READY = false;

    private final ThreadLocal<String> containerHolder = new ThreadLocal<>();
    private final ThreadLocal<Long> memoryPeakHolder = ThreadLocal.withInitial(() -> 0L);

    @Override
    protected void validateRequest(SandboxContext ctx) {
        if (ctx.request == null || ctx.request.getCode() == null || ctx.request.getCode().isBlank()) {
            throw new SandboxException(SandboxError.BAD_REQUEST, "代码为空", "code is blank");
        }
        if (ctx.request.getLanguage() == null || !"python".equalsIgnoreCase(ctx.request.getLanguage())) {
            throw new SandboxException(SandboxError.BAD_REQUEST, "仅支持 Python", "unsupported language");
        }
        List<String> inputList = ctx.request.getInputList();
        ctx.inputList = (inputList == null || inputList.isEmpty()) ? Collections.singletonList("") : inputList;
        List<String> images = ctx.request.getImageBase64List();
        if (images != null && !images.isEmpty() && images.size() != ctx.inputList.size()) {
            throw new SandboxException(SandboxError.BAD_REQUEST, "图像用例数量不匹配", "imageBase64List size mismatch");
        }
    }

    @Override
    protected void saveCodeToFile(SandboxContext ctx) {
        String base = sandboxProperties.getTmpDir();
        if (!FileUtil.exist(base)) {
            FileUtil.mkdir(base);
        }
        String work = base + File.separator + UUID.randomUUID();
        FileUtil.mkdir(work);
        ctx.workDir = new File(work);
        ctx.sourceFile = FileUtil.writeString(ctx.request.getCode(), new File(ctx.workDir, MAIN_FILE), StandardCharsets.UTF_8);
        if (ctx.sourceFile == null || !ctx.sourceFile.exists()) {
            throw new SandboxException(SandboxError.SYSTEM_ERROR, "源码写入失败", "write source file failed");
        }
        List<String> images = ctx.request.getImageBase64List();
        if (images != null && !images.isEmpty()) {
            ctx.imageDir = new File(work + "-images");
            FileUtil.mkdir(ctx.imageDir);
            List<String> imageInputs = new ArrayList<>();
            int totalBytes = 0;
            try {
                for (int i = 0; i < images.size(); i++) {
                    byte[] bytes = Base64.getDecoder().decode(images.get(i));
                    if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                        throw new SandboxException(SandboxError.BAD_REQUEST, "题图大小无效", "invalid image size");
                    }
                    totalBytes += bytes.length;
                    if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                        throw new SandboxException(SandboxError.BAD_REQUEST, "题图总大小超限", "total image size exceeded");
                    }
                    File imageFile = new File(ctx.imageDir, ".image_" + i + ".jpg");
                    FileUtil.writeBytes(bytes, imageFile);
                    imageInputs.add(IMAGE_DIR + "/" + imageFile.getName());
                }
                // 学生程序从 stdin 读到的是容器内图片路径，而不是外网 URL。
                ctx.inputList = imageInputs;
            } catch (IllegalArgumentException e) {
                throw new SandboxException(SandboxError.BAD_REQUEST, "题图数据无效", "invalid image base64");
            }
        }
    }

    @Override
    protected void compile(SandboxContext ctx) {
        DockerClient client = DockerHelper.client();
        String containerId = null;
        try {
            String image = sandboxProperties.getDocker().getPythonImage();
            ensureImage(client, image);
            boolean imageTask = ctx.request.getImageBase64List() != null && !ctx.request.getImageBase64List().isEmpty();
            containerId = createSandboxContainer(client, ctx.workDir.getAbsolutePath(),
                    ctx.imageDir == null ? null : ctx.imageDir.getAbsolutePath(), image, imageTask);
            // 只解析语法，不生成 __pycache__；挂载的代码目录对容器用户可能不可写。
            ExecuteMessage syntaxMsg = execInContainer(
                    client,
                    containerId,
                    new String[]{"python3", "-c", "import ast; ast.parse(open('/app/main.py', encoding='utf-8').read(), filename='/app/main.py')"},
                    "",
                    COMPILE_TIMEOUT_MS
            );
            if (syntaxMsg.getExitValue() == null || syntaxMsg.getExitValue() != 0) {
                throw new SandboxException(SandboxError.COMPILE_ERROR, "语法检查失败", firstDetail(syntaxMsg));
            }
            containerHolder.set(containerId);
            containerId = null;
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            log.error("Docker Python 语法检查阶段异常", e);
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
                String inputFileName = ".input_" + i + ".txt";
                FileUtil.writeString(input == null ? "" : input, new File(ctx.workDir, inputFileName), StandardCharsets.UTF_8);
                String inputPathInContainer = WORK_DIR + "/" + inputFileName;
                String runShell = "python3 " + WORK_DIR + "/" + MAIN_FILE + " < " + inputPathInContainer;
                ExecuteMessage runMsg = execInContainer(
                        client,
                        containerId,
                        new String[]{"sh", "-lc", runShell},
                        "",
                    ctx.request.getImageBase64List() == null || ctx.request.getImageBase64List().isEmpty()
                            ? RUN_TIMEOUT_MS : 60_000L
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
                String stdout = StrUtil.nullToEmpty(runMsg.getMessage()).trim();
                if (ctx.request.getImageBase64List() != null && !ctx.request.getImageBase64List().isEmpty()) {
                    // Ultralytics 首次启动可能在 stdout 打印 settings 提示；
                    // 图像题约定最后一个非空行才是学生的 JSON 答案。
                    stdout = lastNonBlankLine(stdout);
                }
                ctx.outputList.add(stdout);
            }
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            log.error("Docker Python 运行阶段异常", e);
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
        DockerClient client = null;
        try {
            if (StrUtil.isNotBlank(containerId)) {
                try {
                    client = DockerHelper.client();
                    client.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception e) {
                    log.warn("清理 Python 判题容器失败, containerId={}", containerId, e);
                }
            }
        } finally {
            containerHolder.remove();
            memoryPeakHolder.remove();
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignore) {
                }
            }
            try {
                super.cleanup(ctx);
            } finally {
                if (ctx.imageDir != null) {
                    FileUtil.del(ctx.imageDir);
                }
            }
        }
    }

    @Override
    protected ExecuteCodeResponse buildSuccessResponse(SandboxContext ctx) {
        ExecuteCodeResponse resp = super.buildSuccessResponse(ctx);
        if (resp.getJudgeInfo() == null) {
            resp.setJudgeInfo(new JudgeInfo());
        }
        resp.getJudgeInfo().setMemory(bytesToKb(memoryPeakHolder.get()));
        return resp;
    }

    @Override
    protected ExecuteCodeResponse buildFailResponse(SandboxContext ctx, SandboxException e) {
        ExecuteCodeResponse resp = super.buildFailResponse(ctx, e);
        if (resp.getJudgeInfo() == null) {
            resp.setJudgeInfo(new JudgeInfo());
        }
        resp.getJudgeInfo().setMemory(bytesToKb(memoryPeakHolder.get()));
        return resp;
    }

    private static String createSandboxContainer(
            DockerClient client, String hostWorkDir, String hostImageDir, String image, boolean imageTask) {
        Map<String, String> tmpFs = new HashMap<>();
        tmpFs.put("/tmp", "rw,noexec,nosuid,size=64m");
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withMemory((imageTask ? 1536L : 256L) * 1024 * 1024)
                .withMemorySwap((imageTask ? 1536L : 256L) * 1024 * 1024)
                .withPidsLimit(64L)
                .withCpuPeriod(100_000L)
                .withCpuQuota(100_000L)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of(
                        "no-new-privileges:true",
                        "apparmor=docker-default"
                ))
                .withBinds(buildBinds(hostWorkDir, hostImageDir))
                .withTmpFs(tmpFs);
        CreateContainerResponse resp = client.createContainerCmd(image)
                .withName("sandbox_py_" + UUID.randomUUID().toString().replace("-", ""))
                .withHostConfig(hostConfig)
                .withWorkingDir(WORK_DIR)
                .withUser("1000:1000")
                .withCmd("sh", "-c", "sleep 3600")
                .exec();
        client.startContainerCmd(resp.getId()).exec();
        return resp.getId();
    }

    static List<Bind> buildBinds(String hostWorkDir, String hostImageDir) {
        Bind code = new Bind(hostWorkDir, new Volume(WORK_DIR), AccessMode.rw);
        if (hostImageDir == null) {
            return List.of(code);
        }
        return List.of(code, new Bind(hostImageDir, new Volume(IMAGE_DIR), AccessMode.ro));
    }

    private static void ensureImage(DockerClient client, String image) throws InterruptedException {
        if (IMAGE_READY) {
            return;
        }
        synchronized (DockerPythonCodeSandbox.class) {
            if (IMAGE_READY) {
                return;
            }
            try {
                client.inspectImageCmd(image).exec();
                IMAGE_READY = true;
                return;
            } catch (NotFoundException ignore) {
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

    private static String lastNonBlankLine(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] lines = output.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private static long bytesToKb(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return (bytes + BYTES_PER_KB - 1) / BYTES_PER_KB;
    }

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
