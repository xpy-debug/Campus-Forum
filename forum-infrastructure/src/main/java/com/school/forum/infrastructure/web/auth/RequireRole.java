package com.school.forum.infrastructure.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口需要指定角色才能访问。可加在方法或 Controller 类上。
 *
 * <p><b>与 {@link RequireLogin} 的关系：</b>本注解蕴含登录要求——
 * 未登录显然也不满足角色要求，所以只写 {@code @RequireRole} 即可，
 * 不必再叠一个 {@code @RequireLogin}。
 *
 * <p><b>为什么权限判断必须放在拦截器，而不是 Service 里写 {@code if (isAdmin())}：</b>
 * 越权漏洞几乎从不出现在「判断写错了」，而是出现在「某个接口忘了判断」。
 * 判断散落在各 Service 中时，「漏了一处」和「写对了一处」在代码 review 时长得一模一样；
 * 集中到拦截器后，漏掉的概率与「新增接口时忘记加注解」成正比，
 * 而这可以用一个测试穷举覆盖（遍历全部管理端接口，断言普通用户 Token 全被拒绝）。
 *
 * <p><b>本项目只用到三档角色</b>（普通用户 0 / 版主 1 / 管理员 2），
 * 且是包含关系而非互斥集合，故用「最低角色码」而不是角色列表来表达：
 * 一个 {@code @RequireRole(LoginUser.ROLE_ADMIN)} 就等价于
 * 「角色 ≥ 2」，比写成 {@code @RequireRole({ROLE_ADMIN})} 更不容易误用。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 允许访问的最低角色码，与 {@code t_user.role} 取值一致。
     *
     * @see LoginUser#ROLE_ADMIN
     * @see LoginUser#ROLE_MODERATOR
     */
    int value();
}
