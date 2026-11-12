package com.spingbootinit.model.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理员设置用户账号状态
 */
@Data
public class UserAdminSetStatusRequest {

    @NotNull(message = "用户 id 不能为空")
    @Schema(type = "string", description = "用户 ID，前端按字符串传递")
    private Long userId;

    /**
     * 账号状态：0-禁用，1-正常
     */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态取值非法")
    @Max(value = 1, message = "状态取值非法")
    private Integer status;

    /**
     * 禁用时可选：限制小时数。null 或 0 表示永久禁用，直至管理员手动启用。
     */
    @Min(value = 0, message = "禁用时长大于等于 0")
    private Integer banHours;
}
