package com.spingbootinit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主服务调用独立 Sandbox 判题服务的 HTTP 配置。
 * <p>
 * 须与 {@code Sandbox} 模块 {@code sandbox.auth.key}、{@code POST /api/sandbox} 一致。
 */
@Data
@ConfigurationProperties(prefix = "codesandbox")
public class CodeSandboxProperties {

    /**
     * 沙箱实现：example / remote / thirdParty
     */
    private String type = "example";

    /**
     * Sandbox 判题接口完整 URL，例如 {@code http://127.0.0.1:8080/api/sandbox}
     */
    private String url = "http://127.0.0.1:8080/api/sandbox";

    /**
     * 鉴权请求头名称，与 Sandbox {@code auth} 头对应
     */
    private String authHeaderName = "auth";

    /**
     * 鉴权密钥，与 Sandbox {@code sandbox.auth.key} 一致
     */
    private String authKey = "";
}
