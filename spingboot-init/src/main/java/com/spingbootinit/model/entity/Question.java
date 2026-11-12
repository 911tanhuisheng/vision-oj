package com.spingbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.question.JudgeConfig;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 题目
 * @TableName question
 */
@TableName(value ="question",autoResultMap = true)
@Data
public class Question extends BaseEntity implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 题目类型：TEXT / IMAGE_OBJECT_COUNT / IMAGE_CLASSIFICATION /
     * IMAGE_OBJECT_DETECTION / IMAGE_OCR / IMAGE_ANALYSIS
     */
    private String questionType;

    /**
     * 图像题题图地址（仅用于题面展示）
     */
    private String imageUrl;

    /** 图像识别模型：YOLO_GENERAL / YOLO_HELMET / EASYOCR_ZH_EN / OPENCV_ANALYSIS */
    private String visionModelKey;

    /** 题图来源：UPLOAD / LOCAL_COMFYUI / DASHSCOPE */
    private String generationSource;

    /** 生图模型标识；手动上传时为空 */
    private String generationModel;

    /**
     * 目标计数判题时允许的单类数量误差
     */
    private Integer countTolerance;

    /**
     * 标签列表（json 数组）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 题目答案
     */
    private String answer;

    /**
     * 题目提交数
     */
    private Integer submitNum;

    /**
     * 题目通过数
     */
    private Integer acceptedNum;

    /**
     * 判题用例（json 数组）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<JudgeCase> judgeCase;

    /**
     * 判题配置（json 对象）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JudgeConfig judgeConfig;

    /**
     * 点赞数
     */
    private Integer thumbNum;

    /**
     * 收藏数
     */
    private Integer favourNum;

    /**
     * 创建用户 id
     */
    private Long userId;



    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
