package com.school.forum.user.vo;

/**
 * 登录结果。
 *
 * <p>令牌与用户资料一起返回，前端登录后不必再发一次 {@code /auth/me}
 * 就能渲染出顶栏——登录是整条链路里最该省往返的一次请求。
 *
 * <p><b>令牌字段是平铺的，没有嵌套成 {@code token} 对象。</b>用 {@code @JsonUnwrapped}
 * 也能达到同样的 JSON 结构，但那要求序列化框架正确支持「展开 record 组件」，
 * 属于把接口结构押在框架行为上。多写三个字段名换来确定性，值得。
 * 平铺的另一个好处是与 {@code /auth/refresh} 的返回结构一致——
 * 两个接口都产出「一组令牌」，前端的续期逻辑才能复用同一段代码。
 *
 * @param accessToken  访问令牌，随每个请求放在 {@code Authorization} 头里
 * @param refreshToken 刷新令牌，用于访问令牌过期后换取新的访问令牌
 * @param expiresIn    访问令牌剩余有效期（秒），前端可据此提前续期
 * @param user         用户资料
 */
public record LoginVO(String accessToken,
                      String refreshToken,
                      long expiresIn,
                      UserInfoVO user) {

    public static LoginVO of(TokenVO token, UserInfoVO user) {
        return new LoginVO(token.accessToken(), token.refreshToken(), token.expiresIn(), user);
    }
}
