package com.school.forum.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 *
 * <p>RefreshToken 只在这一个接口上被使用——这正是把它单独发一个令牌的理由：
 * 即使它被泄露，攻击者也只能换到 AccessToken，无法直接冒充用户做其他操作。
 * 服务端还可以随时删除 Redis 中的对应记录使其失效。
 */
@Data
public class RefreshRequest {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
