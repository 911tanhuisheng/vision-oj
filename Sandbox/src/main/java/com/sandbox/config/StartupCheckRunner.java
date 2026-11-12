package com.sandbox.config;

import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

@Slf4j
@Component
public class StartupCheckRunner implements ApplicationRunner {

    private final SandboxProperties sandboxProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public StartupCheckRunner(SandboxProperties sandboxProperties, StringRedisTemplate stringRedisTemplate) {
        this.sandboxProperties = sandboxProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        SandboxAuthKeyPolicy.requireStrong(sandboxProperties.getAuth().getKey());
        checkTmpDir();
        checkRedis();
        checkDocker();
    }

    private void checkTmpDir() {
        String tmpDir = sandboxProperties.getTmpDir();
        File dir = new File(tmpDir);
        if (!FileUtil.exist(dir)) {
            FileUtil.mkdir(dir);
        }

        if (!dir.exists() || !dir.isDirectory() || !dir.canWrite()) {
            log.error("Sandbox tmp dir is not writable, path={}", dir.getAbsolutePath());
            return;
        }

        log.info("Sandbox tmp dir ready, path={}", dir.getAbsolutePath());
    }

    private void checkRedis() {
        try {
            String pong = stringRedisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            log.info("Redis connected successfully, ping={}", pong);
        } catch (Exception e) {
            if (sandboxProperties.getRateLimit().isFallbackEnabled()) {
                log.warn("Redis unavailable, local rate limit fallback will be used", e);
            } else {
                log.error("Redis unavailable and local rate limit fallback is disabled", e);
            }
        }
    }

    private void checkDocker() {
        if (!sandboxProperties.getDocker().isStartupCheckEnabled()) {
            log.info("Docker startup check skipped");
            return;
        }

        DockerClient client = null;
        try {
            client = DockerHelper.client();
            String dockerVersion = client.versionCmd().exec().getVersion();
            log.info("Docker connected successfully, version={}", dockerVersion);
            checkImages(client);
        } catch (Exception e) {
            log.error("Docker unavailable, sandbox execution will fail until Docker is ready", e);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void checkImages(DockerClient client) {
        List<String> images = List.of(
                sandboxProperties.getDocker().getJavaImage(),
                sandboxProperties.getDocker().getPythonImage(),
                sandboxProperties.getDocker().getCppImage()
        );

        for (String image : images) {
            try {
                client.inspectImageCmd(image).exec();
                log.info("Docker image ready, image={}", image);
            } catch (NotFoundException e) {
                log.warn("Docker image not found locally, image={}, pull it before production deployment", image);
            } catch (Exception e) {
                log.warn("Docker image check failed, image={}", image, e);
            }
        }
    }
}
