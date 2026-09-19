package com.school.forum.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.infrastructure.web.auth.LoginUser;
import com.school.forum.user.auth.JwtTokenProvider;
import com.school.forum.user.convert.UserConverter;
import com.school.forum.user.dto.LoginRequest;
import com.school.forum.user.dto.RegisterRequest;
import com.school.forum.user.entity.User;
import com.school.forum.user.mapper.UserMapper;
import com.school.forum.user.service.AuthService;
import com.school.forum.user.vo.LoginVO;
import com.school.forum.user.vo.TokenVO;
import com.school.forum.user.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 注册登录的实现。
 *
 * <p>贯穿本类的两条原则：
 * <ul>
 *   <li><b>Redis 只做优化，数据库约束才是正确性的最后防线。</b>
 *       注册锁、登录失败计数都可能在 Redis 重启后丢失，真正保证「用户名不重复」的是
 *       {@code uk_username} 唯一索引；代码里对这两者都有兜底。</li>
 *   <li><b>不向调用方区分「用户不存在」和「密码错误」。</b>区分开来就等于提供了
 *       一个用户名枚举接口，攻击者可以先批量确认哪些账号真实存在，再针对性爆破。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 连续失败多少次锁定 */
    private static final int MAX_LOGIN_FAIL = 5;

    /** 锁定时长。同时也是失败计数的窗口：过了这段时间计数自动清零 */
    private static final Duration LOGIN_FAIL_TTL = Duration.ofMinutes(15);

    /** 注册锁的持有时长。取值只需覆盖「查重 + BCrypt + INSERT」的耗时 */
    private static final long REGISTER_LOCK_SECONDS = 10L;

    /**
     * 用户不存在时用来消耗时间的假密文（明文是随机的，永远不会匹配成功）。
     *
     * <p>没有它，登录接口的响应时间就是一个用户名探测器：账号存在时要跑一次
     * BCrypt 校验（约 100ms），不存在时直接返回（约 1ms）。即使返回的错误信息完全一致，
     * 攻击者靠计时也能把真实用户名筛出来。
     */
    private static final String DUMMY_HASH = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;

    // ==================== 注册 ====================

    @Override
    public Long register(RegisterRequest request) {
        String username = request.getUsername();
        // 用户名统一转小写后存储：否则 Alice 和 alice 会是两个账号，
        // 而用户永远记不清自己当初注册时用的是哪个大小写
        String normalized = username.toLowerCase();

        RLock lock = redissonClient.getLock(RedisKey.registerLock(normalized));
        boolean locked = tryLock(lock);
        try {
            if (existsByUsername(normalized)) {
                throw new BizException(ErrorCode.USERNAME_EXISTS);
            }
            User user = buildUser(request, normalized);
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                // Redis 锁只挡住了大部分并发，唯一索引才是最终保证。
                // 走到这里说明另一个请求刚好也通过了查重，按唯一索引名给出准确提示
                throw new BizException(duplicateKeyError(e));
            }
            log.info("用户注册成功 userId={} username={}", user.getId(), normalized);
            return user.getId();
        } finally {
            unlock(lock, locked);
        }
    }

    private User buildUser(RegisterRequest request, String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setEmail(blankToNull(request.getEmail()));
        user.setStudentNo(blankToNull(request.getStudentNo()));
        user.setCollege(blank(request.getCollege()));
        user.setMajor(blank(request.getMajor()));
        // 显式写出下面这些字段，而不是依赖数据库默认值：实体的默认字段是 null，
        // 一旦有代码路径走 insert 后立刻用返回值（如这里返回 userId 后立刻查缓存），
        // 就会读到 null。让对象在内存里就是完整状态，少一类「只在读库后才正确」的坑
        user.setAvatar("");
        user.setBio("");
        user.setRole(User.ROLE_USER);
        user.setStatus(User.STATUS_NORMAL);
        user.setGender(0);
        user.setLevel(1);
        user.setExp(0);
        user.setPostCount(0);
        user.setFollowerCount(0);
        user.setFollowingCount(0);
        return user;
    }

    private boolean existsByUsername(String username) {
        return userMapper.exists(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    // ==================== 登录 ====================

    @Override
    public LoginVO login(LoginRequest request, String clientIp) {
        String username = request.getUsername().toLowerCase();
        String failKey = RedisKey.loginFail(username);

        if (failCount(failKey) >= MAX_LOGIN_FAIL) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }

        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        // 注意这里用的是 & 而非 &&：账号不存在时也要跑一次 BCrypt，
        // 保证两条分支的耗时接近。详见 DUMMY_HASH 的说明
        boolean matched = passwordEncoder.matches(request.getPassword(),
                user == null ? DUMMY_HASH : user.getPassword());
        if (user == null || !matched) {
            recordLoginFail(failKey);
            throw new BizException(ErrorCode.PASSWORD_INCORRECT);
        }

        // 账号状态在验证密码之后才检查：否则不知道密码的人也能通过错误码
        // 判断出「这个号被封了」，同样是一种信息泄露
        checkStatus(user);

        LoginUser loginUser = new LoginUser(user.getId(), user.getNickname(),
                user.getRole(), newSessionId());
        tokenProvider.whitelist(loginUser);
        redis.delete(failKey);
        updateLastLogin(user.getId(), clientIp);

        log.info("用户登录成功 userId={} username={} ip={}", user.getId(), username, clientIp);
        return LoginVO.of(
                new TokenVO(tokenProvider.createAccessToken(loginUser),
                        tokenProvider.createRefreshToken(loginUser),
                        tokenProvider.accessTtlSeconds()),
                UserConverter.toUserInfoVO(user));
    }

    @Override
    public TokenVO refresh(String refreshToken) {
        LoginUser session = tokenProvider.verifyRefresh(refreshToken);
        if (session == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 重新读库而不是直接信任令牌里的声明：昵称可能改过，
        // 账号也可能在这 7 天里被封禁——刷新正是拦截这类账号的最后一次机会
        User user = userMapper.selectById(session.userId());
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        checkStatus(user);

        LoginUser loginUser = new LoginUser(user.getId(), user.getNickname(),
                user.getRole(), session.sessionId());
        tokenProvider.whitelistAccess(loginUser);
        return new TokenVO(tokenProvider.createAccessToken(loginUser), refreshToken,
                tokenProvider.accessTtlSeconds());
    }

    @Override
    public void logout(String sessionId) {
        tokenProvider.revoke(sessionId);
    }

    @Override
    public UserInfoVO profile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return UserConverter.toUserInfoVO(user);
    }

    // ==================== 内部方法 ====================

    private void checkStatus(User user) {
        int status = user.getStatus() == null ? User.STATUS_NORMAL : user.getStatus();
        ErrorCode error = switch (status) {
            case User.STATUS_MUTED -> null;   // 禁言只限制发言，不影响登录
            case User.STATUS_BANNED -> ErrorCode.ACCOUNT_BANNED;
            case User.STATUS_CANCELLED -> ErrorCode.ACCOUNT_DISABLED;
            default -> null;
        };
        if (error != null) {
            throw new BizException(error);
        }
    }

    private long failCount(String failKey) {
        String value = redis.opsForValue().get(failKey);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 键被别的写入方式污染了，当作没有失败过。
            // 登录失败计数只用于防爆破，不值得为它让正常用户登不进来
            return 0;
        }
    }

    private void recordLoginFail(String failKey) {
        Long fails = redis.opsForValue().increment(failKey);
        // 只在第一次失败时设置过期时间。每次都设置的话，持续攻击会让计数
        // 永不过期，攻击者反而能通过「偶尔错一次」把合法用户永久锁在门外
        if (fails != null && fails == 1L) {
            redis.expire(failKey, LOGIN_FAIL_TTL);
        }
    }

    private void updateLastLogin(Long userId, String clientIp) {
        // 用 UpdateWrapper 只更新这两列，不做整对象更新：
        // 整对象更新会把并发场景下已经变化的其他字段覆盖回旧值
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getLastLoginTime, LocalDateTime.now())
                .set(User::getLastLoginIp, clientIp == null ? "" : clientIp));
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, REGISTER_LOCK_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SERVICE_DEGRADED);
        }
    }

    private void unlock(RLock lock, boolean locked) {
        // 判 isHeldByCurrentThread 再释放：锁可能已因超时被 Redisson 自动释放，
        // 此时直接 unlock 会抛异常，掩盖掉真正的业务异常
        if (locked && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private ErrorCode duplicateKeyError(DuplicateKeyException e) {
        String message = e.getMessage();
        // 靠索引名区分是哪个唯一键冲突。MyBatis-Plus 已经把 SQLException 包了一层，
        // 拿不到结构化的错误码，只能匹配文本——所以这只是提示文案的优化，
        // 判断错了最多是提示不够精确，不影响数据一致性
        if (message != null && message.contains("uk_student_no")) {
            return ErrorCode.STUDENT_NO_EXISTS;
        }
        return ErrorCode.USERNAME_EXISTS;
    }

    private String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String blank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
