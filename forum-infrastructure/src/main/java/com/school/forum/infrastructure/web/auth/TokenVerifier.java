package com.school.forum.infrastructure.web.auth;

/**
 * 令牌校验的抽象。实现方是 forum-user 的 {@code JwtTokenProvider}。
 *
 * <p><b>这个接口存在的唯一理由是解开一个循环：</b>拦截器属于基础设施层
 * （forum-forum 的 Controller 也要靠它拿登录态），而 JWT 的签发与密钥管理
 * 属于用户域。若把拦截器放进 forum-user，forum-forum 就要依赖 forum-user 的内部包；
 * 若把 JWT 实现放进 infrastructure，infrastructure 就带上了业务语义。
 * 让 infrastructure 定义接口、forum-user 提供实现，两边都不必越界。
 *
 * <p>运行期由 Spring 注入实现，编译期 infrastructure 对它一无所知。
 */
public interface TokenVerifier {

    /**
     * 校验令牌并返回登录身份。
     *
     * <p><b>契约：校验失败必须返回 null，不得抛异常。</b>因为本方法在
     * 「登录可选」的公开接口上也会被调用（带了 token 就顺便识别用户），
     * 此时一个过期的 token 属于正常情况，不该中断请求。
     * 真正的「必须登录」由 {@link AuthInterceptor} 依据 {@link RequireLogin} 判定。
     *
     * @param token 不带 {@code Bearer } 前缀的原始令牌
     * @return 登录身份；令牌无效、过期或已登出时返回 null
     */
    LoginUser verify(String token);
}
