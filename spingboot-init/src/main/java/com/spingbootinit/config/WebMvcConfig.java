package com.spingbootinit.config;

import com.spingbootinit.common.iterceptor.JwtInterceptor;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截注册器和跨域问题
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                // 注意：已配置 context-path=/api，这里写业务路径即可（不要再带 /api）
                .addPathPatterns(
                        "/file/**",
                        "/user/update/**",
                        "/user/get/**",
                        "/user/session/status",
                        "/user/session/events",
                        "/user/admin/**",
                        "/user/account/**",
                        "/user/check-in/**",
                        "/question/add/**",
                        "/question/**",
                        "/question_submit/**",
                        "/vision/**",
                        "/contest/join",
                        "/contest/admin/**",
                        "/blog/post/add",
                        "/blog/post/update",
                        "/blog/post/delete",
                        "/blog/post/my/page",
                        "/blog/post/mine/get",
                        "/blog/post/like/toggle",
                        "/blog/comment/add",
                        "/blog/comment/delete",
                        "/blog/comment/like/toggle",
                        "/bailian_assist/**",
                        "/ai/models/**",
                        "/code_validate/**",
                        "/notification/**",
                        "/announcement/admin/**")
                .excludePathPatterns(
                        "/user/login",
                        "/user/refresh",
                        "/user/register",
                        "/captcha",
                        "/question/page",
                        "/question/public/**",
                        "/blog/post/page",
                        "/blog/post/public/**",
                        "/blog/comment/page",
                        "/announcement/public/**",
                        "/doc.html",
                        "/doc.html/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(1000);
    }
}
