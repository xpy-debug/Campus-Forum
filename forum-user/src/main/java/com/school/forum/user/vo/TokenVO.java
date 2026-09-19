package com.school.forum.user.vo;

/**
 * 令牌对。
 *
 * @param accessToken  访问令牌，放在 {@code Authorization: Bearer} 头里
 * @param refreshToken 刷新令牌，仅用于换取新的访问令牌
 * @param expiresIn    访问令牌剩余有效期（秒），前端据此安排提前续期
 */
public record TokenVO(String accessToken, String refreshToken, long expiresIn) {
}
