package com.spingbootinit.model.dto.question;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建请求
 */
@Data
public class QuestionAddRequest implements Serializable {

    /**
     * 标题
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255个字符")
    private String title;

    /**
     * 内容
     */
    @NotBlank(message = "内容不能为空")
    private String content;

    private String questionType = "TEXT";

    private String imageUrl;

    private String visionModelKey = "YOLO_GENERAL";

    private String generationSource;

    private String generationModel;

    private Integer countTolerance = 0;

    /**
     * 标签列表
     */
    @NotEmpty(message = "标签列表不能为空")
    @Size(max = 10, message = "标签数量不能超过10个")
    private List<@NotBlank(message = "标签不能为空") String> tags;

    /**
     * 题目答案
     */
    @NotBlank(message = "答案不能为空")
    private String answer;

    /**
     * 判题用例
     */
    @NotEmpty(message = "判题用例不能为空")
    @Size(max = 20, message = "判题用例数量不能超过20个")
    private List<@Valid @NotNull(message = "判题用例项不能为空") JudgeCase> judgeCase;

    /**
     * 判题配置
     */
    @NotNull(message = "判题配置不能为空")
    @Valid
    private JudgeConfig judgeConfig;

    private static final long serialVersionUID = 1L;
}
