package com.spingbootinit.model.vo.questionsubmitvo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交成功后的返回：普通用户仅含题目与本次提交序号；管理员额外可见数据库主键。
 */
@Data
public class QuestionSubmitCreatedVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;

    /**
     * 当前用户在该题目下的第几次提交（从 1 递增）
     */
    private Long submitNo;

    /**
     * 当前用户在全站所有题目提交中的次序（从 1 递增），与「我的全部提交」列表中的 submitNo 一致，供前端匹配行并轮询更新。
     */
    private Long globalSubmitNo;

    /**
     * 仅管理员返回：数据库中的提交主键（字符串避免前端 Number 精度丢失）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String dbId;
}
