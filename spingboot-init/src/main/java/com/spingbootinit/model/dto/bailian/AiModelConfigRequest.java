package com.spingbootinit.model.dto.bailian;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiModelConfigRequest {
    @Size(max = 100)
    private String modelId;

    /** 更新模型时可留空以保留原密钥。 */
    @Size(max = 512)
    private String apiKey;
}
