package com.spingbootinit.model.dto.bailian;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProblemStructureRequest {
    @NotBlank(message = "OCR 文本不能为空")
    @Size(max = 20000, message = "OCR 文本不能超过 20000 字符")
    private String ocrText;

    /** OCR：整理已有题目；GENERATE：根据教师要求原创题目。 */
    @Size(max = 16)
    private String mode = "OCR";

    @Size(max = 20)
    private String providerCode;

    @Size(max = 100)
    private String modelId;
}
