package com.sandbox.aop;

import com.sandbox.annotation.RateLimit;
import com.sandbox.config.SandboxProperties;
import com.sandbox.service.RedisRateLimiter;
import com.sandbox.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitAspectTest {

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void authenticatedBackendUserGetsOwnRateLimitBucket() throws Throwable {
        MockHttpServletRequest request = request("shared-key", "42");
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.allow(eq("rate_limit:sandbox:uid:42"), anyLong(), anyLong())).thenReturn(true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = new RateLimitAspect(limiter, properties(), mock(JwtUtils.class))
                .around(joinPoint, annotation());

        assertEquals("ok", result);
        verify(limiter).allow(eq("rate_limit:sandbox:uid:42"), anyLong(), anyLong());
    }

    @Test
    void untrustedUserHeaderFallsBackToSourceAddress() throws Throwable {
        MockHttpServletRequest request = request("wrong-key", "42");
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.allow(eq("rate_limit:sandbox:ip:127.0.0.1"), anyLong(), anyLong())).thenReturn(true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("ok");

        new RateLimitAspect(limiter, properties(), mock(JwtUtils.class)).around(joinPoint, annotation());

        verify(limiter).allow(eq("rate_limit:sandbox:ip:127.0.0.1"), anyLong(), anyLong());
    }

    private static MockHttpServletRequest request(String key, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("auth", key);
        request.addHeader("X-OJ-User-Id", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static SandboxProperties properties() {
        SandboxProperties properties = new SandboxProperties();
        properties.getAuth().setKey("shared-key");
        return properties;
    }

    private static RateLimit annotation() {
        RateLimit annotation = mock(RateLimit.class);
        when(annotation.key()).thenReturn("sandbox");
        return annotation;
    }
}
