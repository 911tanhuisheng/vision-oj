package com.spingbootinit.model.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmailBindingRequest {
    @NotBlank(message = "请输入新邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    private String email;

    @NotBlank(message = "请输入当前登录密码")
    private String password;

    /** 获取验证码时可留空；确认绑定时必须填写6位数字 */
    private String code;
}
