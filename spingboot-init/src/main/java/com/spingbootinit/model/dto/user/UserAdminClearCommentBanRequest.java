package com.spingbootinit.model.dto.user;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理员解除用户「违禁评论」限制。
 */
@Data
public class UserAdminClearCommentBanRequest {

    @NotNull(message = "用户 id 不能为空")
    @Schema(type = "string", description = "用户 ID，前端按字符串传递")
    private Long userId;
}
