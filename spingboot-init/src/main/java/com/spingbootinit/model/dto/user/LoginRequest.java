package com.spingbootinit.model.dto.user;


import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    private String username;   // 用户名或注册邮箱（字段名兼容原登录接口）

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "验证码不能为空")
    private String code;      // 登录验证码（当前登录流程必填）

    /** 浏览器可提供的粗略设备参数；客户端自报，不用于安全判定。 */
    private String deviceDetails;
}
