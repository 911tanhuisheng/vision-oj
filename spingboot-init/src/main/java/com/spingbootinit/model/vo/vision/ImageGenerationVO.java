package com.spingbootinit.model.vo.vision;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageGenerationVO {
    private String imageUrl;
    private String promptId;
    private long seed;
    private long generationMs;
    private String source;
    private String modelId;
}
