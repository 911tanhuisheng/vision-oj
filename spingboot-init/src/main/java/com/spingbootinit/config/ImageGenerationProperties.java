package com.spingbootinit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "image-generation")
public class ImageGenerationProperties {
    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:8188";
    private String checkpoint = "v1-5-pruned-emaonly.safetensors";
    private java.util.List<String> allowedCheckpoints = java.util.List.of("v1-5-pruned-emaonly.safetensors");
    private String cloudBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private int timeoutSeconds = 180;
}
