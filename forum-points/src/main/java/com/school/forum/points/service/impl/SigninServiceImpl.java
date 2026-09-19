package com.school.forum.points.service.impl;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.points.config.PointsProperties;
import com.school.forum.points.convert.PointsConverter;
import com.school.forum.points.mapper.UserSigninMapper;
import com.school.forum.points.service.PointsAccountService;
import com.school.forum.points.service.SigninService;
import com.school.forum.points.vo.SigninResultVO;
import com.school.forum.points.vo.SigninStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 签到的实现。
 *
 * <pre>
 *   POST /points/signin
 *     → EVAL signin.lua：SETBIT 返回值即「今天是不是第一次」，同一次往返拿到本月天数
 *     → 事务 { INSERT t_user_signin + 加积分 + 记流水 }
 *     → 返回余额与本月天数，前端不必再查一次
 * </pre>
 *
 * <p><b>位图与数据库的分工</b>（ADR-007）：位图负责「今天签没签」的廉价判断，
 * 一次 {@code SETBIT} 同时完成判断与写入，不需要额外的分布式锁；
 * {@code t_user_signin} 负责在任何缓存失效后仍能说清事实。两者都拦不住的那部分
 * ——极端并发下两个请求都通过位图判断——由 {@code uk_user_date} 兜底。
 * <b>三层里只有最里面那层是不可失效的</b>，外面两层存在的意义是让绝大多数
 * 请求不必走到最里面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SigninServiceImpl implements SigninService {

    /** 脚本在类加载时读一次，之后常驻内存，避免每次签到都读一遍文件 */
    private static final RedisScript<List> SIGNIN_SCRIPT = loadScript("lua/signin.lua");

    private final StringRedisTemplate redis;
    private final UserSigninMapper userSigninMapper;
    private final PointsAccountService accountService;
    private final PointsProperties properties;

    // ==================== 查询状态 ====================

    @Override
    public SigninStatusVO status(Long userId) {
        LocalDate today = LocalDate.now();
        String yearMonth = YearMonth.from(today).toString();

        // 一次取整月：签到页要画月历，31 行结果集摊在一次索引范围扫描上，
        // 比按天逐次判断便宜得多（见 UserSigninMapper.selectDatesOfMonth）
        List<LocalDate> dates = userSigninMapper.selectDatesOfMonth(userId, yearMonth);

        return PointsConverter.toStatusVO(yearMonth, today, dates,
                continuousDays(userId, today, dates),
                properties.getSigninPoints(),
                properties.getBonusThreshold(),
                properties.getBonusPoints());
    }

    // ==================== 签到 ====================

    @Override
    public SigninResultVO signin(Long userId) {
        LocalDate today = LocalDate.now();
        String yearMonth = YearMonth.from(today).toString();
        String key = RedisKey.signinBitmap(userId, yearMonth);

        // 位偏移量 = 日 - 1：1 号占第 0 位。一个月最多 31 位，
        // 一个用户一个月的存储成本是 4 字节，这是位图相对 Set 的核心优势
        List<?> result = redis.execute(SIGNIN_SCRIPT, List.of(key),
                String.valueOf(today.getDayOfMonth() - 1),
                String.valueOf(Duration.ofDays(properties.getBitmapTtlDays()).toSeconds()));

        boolean alreadySigned = longAt(result, 0) == 1L;
        long bitmapCount = longAt(result, 1);

        if (alreadySigned) {
            // 位图说签过了。不查流水也不加分——这一步是纯粹的快路径，
            // 每天最多被一个用户走一次，却挡掉了所有重复点击
            return new SigninResultVO(false, 0, accountService.balanceOf(userId),
                    countOfMonth(userId, yearMonth), "今天已经签过了");
        }

        int points;
        try {
            points = accountService.earnSignin(userId, today);
        } catch (DuplicateKeyException e) {
            // 位图说没签、数据库说签过了：只可能是位图丢了（Redis 被清或内存淘汰）。
            // 这不是错误状态，今天的签到确实已经生效过，如实告诉用户即可。
            // 落后的位图由 SigninBitmapRepairTask 每天重建，这里不做同步修复——
            // 在用户的请求线程里补一整月的位图会让偶发的慢请求变成常态
            log.warn("位图落后于数据库，已按「今日已签到」处理。userId={}, date={}", userId, today);
            return new SigninResultVO(false, 0, accountService.balanceOf(userId),
                    countOfMonth(userId, yearMonth), "今天已经签过了");
        }

        int signedDays = countOfMonth(userId, yearMonth);
        if (bitmapCount != signedDays) {
            // 位图与数据库不一致时以数据库为准（对外报的就是 signedDays），
            // 位图的 BITCOUNT 在这里的作用是**持续体检**：位图一旦落后就留下日志，
            // 而不是等用户发现日历不对。修复本身交给定时任务
            log.warn("签到位图与数据库不一致，等待修复任务重建。userId={}, bitmap={}, db={}",
                    userId, bitmapCount, signedDays);
        }

        return new SigninResultVO(true, points, accountService.balanceOf(userId), signedDays,
                message(points, signedDays, yearMonth));
    }

    /**
     * 签到成功的提示语。
     *
     * <p>刚好达成全勤时追加一句告知：这是用户唯一能感知到「签到有意义」的时刻，
     * 等下个月奖励到账时他早就忘了自己签满过。
     */
    private String message(int points, int signedDays, String yearMonth) {
        String base = "签到成功，积分 +" + points;
        if (signedDays > properties.getBonusThreshold()) {
            return base + "。本月已达成全勤，" + yearMonth + " 的全勤奖励将在次月初发放";
        }
        return base;
    }

    private int countOfMonth(Long userId, String yearMonth) {
        return userSigninMapper.countByMonth(userId, yearMonth);
    }

    // ==================== 连续天数 ====================

    /**
     * 连续签到天数（从今天或昨天往前数）。
     *
     * <p>「昨天」也算起点：今天还没签不该让昨天刚建立的连续记录归零，
     * 那样用户会在每天早上看到连续天数清零——而傍晚签到时又跳回来。
     *
     * <p><b>跨月要连上月一起算。</b>8/30、8/31、9/1 是一条 3 天的连续记录，
     * 但 9 月 1 日这天，本月数据里只有 1 天。因此走到本月第一天时
     * 再去取上月数据——按需取，只有在连续记录真的跨月时才多一次查询。
     * 上月的集合会被缓存，反复跨月回溯不会重复查库。
     */
    private int continuousDays(Long userId, LocalDate today, List<LocalDate> monthDates) {
        Map<String, Set<LocalDate>> cache = new HashMap<>();
        cache.put(YearMonth.from(today).toString(), new HashSet<>(monthDates));

        Set<LocalDate> currentMonth = cache.get(YearMonth.from(today).toString());
        LocalDate cursor;
        if (currentMonth.contains(today)) {
            cursor = today;
        } else if (currentMonth.contains(today.minusDays(1))) {
            cursor = today.minusDays(1);
        } else {
            return 0;
        }

        int days = 0;
        while (true) {
            YearMonth month = YearMonth.from(cursor);
            Set<LocalDate> signed = cache.computeIfAbsent(month.toString(),
                    key -> new HashSet<>(userSigninMapper.selectDatesOfMonth(userId, key)));
            if (!signed.contains(cursor)) {
                break;
            }
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    // ==================== 工具 ====================

    /**
     * 取 Lua 返回的第 n 个值。
     *
     * <p>按字符串解析而不是直接强转 {@code Long}：{@code StringRedisTemplate} 的
     * 值序列化器是字符串，Lua 表里的整数回来时会变成字符串。
     * 这里两种形态都接受，脚本改返回值类型时不会因为一次 ClassCastException
     * 把签到整个打挂。
     */
    private static long longAt(List<?> values, int index) {
        if (values == null || values.size() <= index) {
            return 0L;
        }
        Object value = values.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static RedisScript<List> loadScript(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new DefaultRedisScript<>(StreamUtils.copyToString(in, StandardCharsets.UTF_8), List.class);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 Lua 脚本失败：" + path, e);
        }
    }
}
