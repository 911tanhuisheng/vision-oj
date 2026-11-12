package com.spingbootinit.model.dto.vision;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ImageGenerateRequest {
    @NotBlank(message = "请输入图像生成描述")
    @Size(max = 5000, message = "图像生成描述不能超过 5000 字符")
    private String prompt;

    private String negativePrompt;
    private String source;
    private String modelId;

    @Min(value = 256, message = "图片宽度不能小于 256")
    @Max(value = 768, message = "6GB 显存建议宽度不超过 768")
    private int width = 512;

    @Min(value = 256, message = "图片高度不能小于 256")
    @Max(value = 768, message = "6GB 显存建议高度不超过 768")
    private int height = 512;

    @Min(value = 10, message = "采样步数不能小于 10")
    @Max(value = 40, message = "采样步数不能大于 40")
    private int steps = 20;

    private Long seed;
}
