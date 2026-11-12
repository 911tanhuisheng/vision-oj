package com.spingbootinit.model.dto.question;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class QuestionUpdateRequest {
    @NotNull(message = "题目 id 不能为空")
    private Long id;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    @NotBlank(message = "题面不能为空")
    @Size(max = 20000, message = "题面长度不能超过20000")
    private String content;

    private String questionType = "TEXT";

    private String imageUrl;

    private String visionModelKey = "YOLO_GENERAL";

    private String generationSource;

    private String generationModel;

    private Integer countTolerance = 0;

    @NotEmpty(message = "标签不能为空")
    private List<@NotBlank(message = "标签不能为空") String> tags;

    @NotBlank(message = "答案不能为空")
    @Size(max = 20000, message = "答案长度不能超过20000")
    private String answer;

    @Valid
    @NotEmpty(message = "判题用例不能为空")
    private List<JudgeCase> judgeCase;

    @Valid
    @NotNull(message = "判题配置不能为空")
    private JudgeConfig judgeConfig;
}
