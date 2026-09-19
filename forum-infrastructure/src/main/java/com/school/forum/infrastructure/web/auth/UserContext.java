package com.school.forum.infrastructure.web.auth;

import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;

/**
 * 当前请求的登录用户上下文。
 *
 * <p><b>为什么用 ThreadLocal 而不是把 userId 当参数层层往下传：</b>
 * 帖子、评论、点赞三个 service 的绝大多数方法都需要知道「谁在操作」，
 * 一路透传会让方法签名膨胀且极易漏传；更要紧的是，一旦某个内部方法
 * 忘记传 userId，它就只能退化成「不带权限校验」的版本，而这恰恰是越权漏洞的温床。
 *
 * <p><b>⚠ 必须在请求结束时清理。</b>Tomcat 的工作线程是复用的，
 * 不清理会让上一个请求的登录态被下一个请求读到——表现为「A 用户偶尔能操作 B 用户的资源」，
 * 这类问题在压测之外几乎不可能复现。清理由 {@link AuthInterceptor#afterCompletion} 负责。
 *
 * <p>本类不依赖 Spring，可被任意层调用；但只有 Web 请求线程里有值，
 * 定时任务、MQ 消费线程中 {@link #get()} 恒为 null，属预期行为。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /** 写入登录态。仅应由 {@link AuthInterceptor} 调用 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 当前登录用户，未登录时为 null */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 当前用户 ID，未登录时为 null。用于「登录可选」的接口（如帖子列表要回显 isLiked） */
    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /**
     * 当前用户 ID，未登录直接抛 10002。
     * <p>供 Service 层做二次防御——即使 Controller 漏标 {@link RequireLogin}，
     * 也不会出现「未登录却能写入」的情况。
     */
    public static Long requireUserId() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user.userId();
    }

    public static boolean isLogin() {
        return HOLDER.get() != null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
