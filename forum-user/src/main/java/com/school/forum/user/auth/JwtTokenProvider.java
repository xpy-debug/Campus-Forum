package com.school.forum.user.auth;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.infrastructure.web.auth.LoginUser;
import com.school.forum.infrastructure.web.auth.TokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 令牌的签发、白名单与校验。
 *
 * <p><b>为什么「签名校验」和「Redis 白名单」写在同一个类里：</b>
 * 它们回答的是同一个问题——「这个令牌现在还有效吗」。纯无状态 JWT 只能证明
 * 「令牌是我签发的、且没被篡改」，却无法表达「用户已经登出」「管理员刚刚把他封禁了」
 * 这类在签发之后才发生的事实。少了白名单，登出就只是前端删掉本地存储，
 * 令牌在有效期内仍然畅通无阻；一旦令牌泄露，服务端没有任何手段补救。
 *
 * <p>代价是每个请求多一次 Redis 查询。这是本项目明确接受的取舍：
 * {@code SISMEMBER} 级别的延迟在毫秒以下，而「能主动失效」是登录态的基本要求。
 * 若将来确实需要省掉这次查询，正确做法是把白名单换成短有效期令牌 + 版本号，
 * 而不是在这里去掉校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider implements TokenVerifier {

    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "type";

    /** 两种令牌用同一个 type 声明区分。刷新令牌拿不到访问权限，靠的就是这一条 */
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** HS256 的密钥长度下限（256 位 = 32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final StringRedisTemplate redis;

    private SecretKey key;

    @PostConstruct
    void initKey() {
        String secret = properties.getSecret();
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "forum.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节，当前 "
                            + bytes.length + " 字节。密钥过短会让 HS256 签名可被暴力破解");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    // ==================== 签发 ====================

    public String createAccessToken(LoginUser user) {
        return create(user, TYPE_ACCESS, Duration.ofMinutes(properties.getAccessTtlMinutes()));
    }

    public String createRefreshToken(LoginUser user) {
        return create(user, TYPE_REFRESH, Duration.ofDays(properties.getRefreshTtlDays()));
    }

    private String create(LoginUser user, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                // sub 存用户 ID。不放 username：用户名可以被改，用它做身份标识
                // 会让「改用户名」变成一次隐式的身份变更
                .subject(String.valueOf(user.userId()))
                // jti 即会话标识。同一个会话的两个令牌共用一个 jti，
                // 登出时按它一次性删掉两条白名单记录
                .id(user.sessionId())
                .claim(CLAIM_NICKNAME, user.nickname())
                .claim(CLAIM_ROLE, user.role())
                .claim(CLAIM_TYPE, type)
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** AccessToken 有效期（秒），用于返回给前端安排续期时机 */
    public long accessTtlSeconds() {
        return Duration.ofMinutes(properties.getAccessTtlMinutes()).toSeconds();
    }

    // ==================== 白名单 ====================

    /** 登录成功后登记两条白名单记录 */
    public void whitelist(LoginUser user) {
        whitelistAccess(user);
        redis.opsForValue().set(RedisKey.refreshToken(user.sessionId()),
                String.valueOf(user.userId()),
                Duration.ofDays(properties.getRefreshTtlDays()));
    }

    /**
     * 只续期 AccessToken 的白名单。
     *
     * <p>刷新接口刻意<b>不</b>重设 RefreshToken 的白名单：那会把它变成滑动过期，
     * 只要用户保持活跃，一个泄露的刷新令牌就能无限续命。
     * 固定在 7 天后失效，用户重新登录一次，是更稳妥的取舍。
     */
    public void whitelistAccess(LoginUser user) {
        redis.opsForValue().set(RedisKey.accessToken(user.sessionId()),
                String.valueOf(user.userId()),
                Duration.ofMinutes(properties.getAccessTtlMinutes()));
    }

    /** 登出：删除该会话的两条白名单记录，令牌立即失效（不等它自然过期） */
    public void revoke(String sessionId) {
        redis.delete(List.of(RedisKey.accessToken(sessionId), RedisKey.refreshToken(sessionId)));
    }

    // ==================== 校验 ====================

    @Override
    public LoginUser verify(String token) {
        return parse(token, TYPE_ACCESS);
    }

    /** 校验刷新令牌。调用方应当把它当成「换一张新通行证」的凭据，而不是访问凭据 */
    public LoginUser verifyRefresh(String token) {
        return parse(token, TYPE_REFRESH);
    }

    /**
     * 校验令牌并还原登录态。任何失败都返回 null，不抛异常——
     * 这是 {@link TokenVerifier} 的契约：调用方只需要知道「能不能放行」，
     * 令牌是过期、被篡改还是已登出，对拦截器而言没有区别。
     */
    private LoginUser parse(String token, String expectedType) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不匹配、已过期、结构损坏都走这里。不记 WARN 级别日志：
            // 公网环境下这类请求每天都在发生，逐条记录只会淹没真正有用的日志
            return null;
        }

        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            return null;
        }
        String sessionId = claims.getId();
        String whitelistKey = TYPE_ACCESS.equals(expectedType)
                ? RedisKey.accessToken(sessionId)
                : RedisKey.refreshToken(sessionId);
        if (!StringUtils.hasText(sessionId) || !Boolean.TRUE.equals(redis.hasKey(whitelistKey))) {
            return null;
        }

        try {
            return new LoginUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_NICKNAME, String.class),
                    claims.get(CLAIM_ROLE, Integer.class),
                    sessionId);
        } catch (NumberFormatException | NullPointerException e) {
            log.warn("令牌声明格式异常，sessionId={}", sessionId);
            return null;
        }
    }
}
