package com.school.forum.infrastructure.web.auth;

import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截器：解析令牌 → 写入 {@link UserContext} → 按需强制登录。
 *
 * <p><b>两个职责刻意分开：</b>
 * <ul>
 *   <li><b>识别</b>（有 token 就认人）：对所有请求生效。帖子列表要在游客视角下
 *       正常渲染，同时又要给已登录用户回显「我点过赞」，所以不能因为没带 token 就拒绝。</li>
 *   <li><b>强制</b>（没登录就拒绝）：只对标注了 {@link RequireLogin} 的接口生效。</li>
 * </ul>
 * 如果只做前者，写接口会裸奔；只做后者，游客态页面就拿不到个性化数据。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenVerifier tokenVerifier;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = resolveUser(request);
        if (user != null) {
            UserContext.set(user);
        }

        // 先判角色：@RequireRole 蕴含登录要求，但两者的拒绝码不同
        // （未登录 10002 / 权限不足 10003），前端需要区分「请登录」与「你无权」
        Integer minRole = requiredRole(handler);
        if (minRole != null) {
            if (user == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED);
            }
            if (user.role() < minRole) {
                log.warn("越权访问被拦截。userId={}, role={}, required={}, uri={}",
                        user.userId(), user.role(), minRole, request.getRequestURI());
                throw new BizException(ErrorCode.FORBIDDEN);
            }
            return true;
        }

        if (!requiresLogin(handler)) {
            return true;
        }
        if (user == null) {
            // 抛业务异常而不是返回 401 状态码：本项目约定 HTTP 状态码恒为 200，
            // 由 GlobalExceptionHandler 统一翻译成 Result.fail(10002)
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }

    /**
     * 请求结束必须清理 ThreadLocal。
     *
     * <p>用 {@code afterCompletion} 而不是 {@code postHandle}：后者在 Controller 抛异常时
     * 根本不会被调用，而「鉴权失败」恰恰是最常见的异常路径。不清理的后果是
     * 复用该线程的下一个请求会继承上一个用户的身份。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private LoginUser resolveUser(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            return tokenVerifier.verify(token);
        } catch (Exception e) {
            // 令牌解析本身出问题（如签名算法不匹配）不应让整个请求 500——
            // 公开接口按游客继续处理，受保护接口由下面的 requiresLogin 拦下
            log.debug("令牌校验异常，按未登录处理。uri={}, err={}", request.getRequestURI(), e.getMessage());
            return null;
        }
    }

    /** 方法或类上任一标注了 {@link RequireLogin} 即视为需要登录 */
    private boolean requiresLogin(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            // 静态资源、Swagger 的 ResourceHttpRequestHandler 等
            return false;
        }
        return method.hasMethodAnnotation(RequireLogin.class)
                || method.getBeanType().isAnnotationPresent(RequireLogin.class);
    }

    /**
     * 取接口要求的最低角色码，未标注则返回 null。
     *
     * <p>方法上的注解优先于类上的：类级注解表达的是「这个 Controller 整体归管理端」，
     * 方法级注解可以在此基础上<b>收紧</b>（但本项目的角色是包含关系，
     * 收紧表现为「要求更高的角色码」）。
     */
    private Integer requiredRole(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequireRole onMethod = method.getMethodAnnotation(RequireRole.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequireRole onClass = method.getBeanType().getAnnotation(RequireRole.class);
        return onClass == null ? null : onClass.value();
    }
}
