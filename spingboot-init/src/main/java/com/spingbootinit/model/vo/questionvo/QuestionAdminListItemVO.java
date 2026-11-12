package com.spingbootinit.model.vo.questionvo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.question.JudgeConfig;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 管理端题目列表项
 */
@Data
public class QuestionAdminListItemVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String title;
    private String content;
    private String questionType;
    private String imageUrl;

    private String generationSource;

    private String generationModel;
    private String visionModelKey;
    private Integer countTolerance;
    private List<String> tags;
    private String answer;
    private Integer submitNum;
    private Integer acceptedNum;
    private List<JudgeCase> judgeCase;
    private JudgeConfig judgeConfig;
    private Integer thumbNum;
    private Integer favourNum;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String userNickname;
    private Date createTime;
    private Date updateTime;
}
