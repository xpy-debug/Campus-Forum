package com.school.forum.infrastructure.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口需要登录才能访问。可加在方法或 Controller 类上。
 *
 * <p><b>为什么用注解而不是在拦截器里配 URL 白名单：</b>
 * URL 白名单把「哪些接口需要登录」这个信息放在了离接口很远的地方，
 * 新增接口时很容易忘记登记，而漏登记的后果是接口裸奔且无人察觉。
 * 注解写在方法上，「需要登录」这件事和接口定义在同一行视野内，
 * 漏标的可能性低得多，review 时也一眼可见。
 *
 * <p>默认所有接口都是公开的（游客可浏览帖子列表与详情，这是论坛的常态），
 * 只有标注了本注解的接口才强制登录。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireLogin {
}
