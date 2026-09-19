package com.school.forum.infrastructure.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把当前登录用户的 ID 注入 Controller 方法参数，由
 * {@link CurrentUserIdResolver} 解析。
 *
 * <pre>{@code
 * @RequireLogin
 * @PostMapping
 * public Result<Long> create(@Valid @RequestBody PostCreateRequest req,
 *                            @CurrentUserId Long userId) { ... }
 * }</pre>
 *
 * <p><b>相比在方法体第一行写 {@code UserContext.getUserId()}：</b>把「依赖登录态」
 * 这件事提到了方法签名上，接口对登录的要求一眼可见，也让 Controller 可以写成
 * 无状态的纯函数——同样的入参必然得到同样的结果，单测时不必再去摆弄 ThreadLocal。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {

    /**
     * 是否要求必须登录。
     *
     * <p>默认 {@code true}：登录态为空时直接报「未登录」。这能挡住
     * 「方法上漏标 {@code @RequireLogin}」这种笔误——否则参数会安静地注入 null，
     * 一直传到下游才以空指针的形式爆出来。
     *
     * <p>帖子列表、详情这类<b>未登录也能看</b>的接口需要显式写
     * {@code required = false}：它们的出参里 {@code isLiked} 依赖登录态，
     * 但登录与否都应当返回内容。
     */
    boolean required() default true;
}
