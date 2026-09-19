package com.school.forum.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>这里的 password 不加长度与格式校验——那些规则是注册时的准入条件，
 * 登录时只需要「是不是这个密码」。对着登录接口做格式校验会泄露信息：
 * 攻击者能从「密码格式不正确」与「用户名或密码错误」两种不同的回应中，
 * 推断出目标密码的长度或字符构成。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 设备标识，由前端生成并持久化（如 {@code web-<uuid>}）。
     * <p>用于区分同一账号的多个登录会话，登出只影响当前设备。
     * 为空时服务端按「未知设备」处理，不影响登录本身。
     */
    private String deviceId;
}
