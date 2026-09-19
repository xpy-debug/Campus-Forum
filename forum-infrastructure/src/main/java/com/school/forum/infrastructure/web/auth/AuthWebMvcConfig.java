package com.school.forum.infrastructure.web.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册鉴权拦截器与 {@link CurrentUserId} 参数解析器。
 *
 * <p>拦截器注册在 {@code /**} 上而不是一份 URL 白名单上：「是否要登录」由
 * {@link RequireLogin} 注解决定，这里不做路径判断。好处是新增接口时无需回来改配置，
 * 也就不存在「加了接口忘了登记白名单」这种漏网之鱼。
 */
@Configuration
@RequiredArgsConstructor
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final TokenVerifier tokenVerifier;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(tokenVerifier)).addPathPatterns("/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdResolver());
    }
}
