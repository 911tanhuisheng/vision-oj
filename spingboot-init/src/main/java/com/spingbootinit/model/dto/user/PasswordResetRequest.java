package com.spingbootinit.model.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PasswordResetRequest {
    @NotBlank(message = "请输入注册邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    private String email;

    @NotBlank(message = "请输入邮件验证码")
    @Pattern(regexp = "^[0-9]{6}$", message = "验证码应为6位数字")
    private String code;

    @NotBlank(message = "请输入新密码")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,20}$", message = "密码须为6～20位字母和数字组合")
    private String newPassword;
}
