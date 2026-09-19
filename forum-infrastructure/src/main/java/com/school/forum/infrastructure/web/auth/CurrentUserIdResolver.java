package com.school.forum.infrastructure.web.auth;

import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentUserId} 标注的方法参数。
 *
 * <p>本类之所以被注册在自定义解析器里、而不是靠 Spring 内置的那批：
 * 内置的 {@code RequestParamMethodArgumentResolver} 带 {@code useDefaultResolution}
 * 时会把「任何简单类型且无注解」的参数都当成请求参数，但自定义解析器排在
 * 它的同名 catch-all 实例之前，因此 {@code @CurrentUserId Long userId}
 * 会先落到这里，不会被误当作 query 参数去取值。
 */
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Long userId = UserContext.getUserId();
        CurrentUserId annotation = parameter.getParameterAnnotation(CurrentUserId.class);
        if (userId == null && (annotation == null || annotation.required())) {
            // 走到这里说明 Controller 用了 @CurrentUserId 却漏标 @RequireLogin。
            // 与其返回 null 让下游 NPE，不如立刻报「未登录」——错误信息更准确，
            // 也顺带在开发期暴露注解漏标的问题
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
