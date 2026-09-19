package com.school.forum.infrastructure.web.auth;

/**
 * 当前登录用户的轻量身份。
 *
 * <p>刻意只带四个字段：身份（userId）、展示名（nickname）、权限（role）
 * 与会话标识（sessionId）。邮箱、学号这类字段不进这里——它们只在「我的资料」
 * 接口用得到，为了少数场景让每个请求都持有一份完整用户对象，
 * 只会扩大敏感信息在内存中的暴露面。
 *
 * @param userId    用户 ID
 * @param nickname  昵称，仅用于日志与展示，不作为权限判断依据
 * @param role      角色 0普通用户 1版主 2管理员
 * @param sessionId 本次登录的会话标识。登出时按它删除 Redis 中的令牌白名单，
 *                  使该设备的令牌立即失效，而不影响同一用户的其他设备
 */
public record LoginUser(Long userId, String nickname, int role, String sessionId) {

    /** 管理员角色码，与 {@code t_user.role} 的取值一致 */
    public static final int ROLE_ADMIN = 2;

    /** 版主角色码 */
    public static final int ROLE_MODERATOR = 1;

    public boolean isAdmin() {
        return role >= ROLE_ADMIN;
    }

    /** 版主及以上 */
    public boolean isModerator() {
        return role >= ROLE_MODERATOR;
    }
}
