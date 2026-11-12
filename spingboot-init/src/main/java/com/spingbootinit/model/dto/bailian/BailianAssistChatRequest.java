package com.spingbootinit.model.dto.bailian;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BailianAssistChatRequest {

    @NotBlank
    @Size(max = 4000)
    private String userMessage;

    @Size(max = 32)
    private String questionId;

    @Size(max = 500)
    private String questionTitle;

    @Size(max = 12000)
    private String questionContent;

    @Size(max = 32)
    private String language;

    @Size(max = 64)
    private String questionType;

    /** 评测限制、图像题协议等公开元信息（JSON 文本）。 */
    @Size(max = 3000)
    private String judgeContext;

    /** 仅包含题面公开样例，不包含隐藏测试点。 */
    @Size(max = 6000)
    private String sampleCases;

    /** 最近一次“运行”结果，用于分析 CE/RE/WA/TLE/MLE。 */
    @Size(max = 10000)
    private String runResult;

    @Size(max = 20000)
    private String code;

    private List<BailianAssistTurnDTO> history = new ArrayList<>();

    @Size(max = 20)
    private String providerCode;

    @Size(max = 100)
    private String modelId;
}
