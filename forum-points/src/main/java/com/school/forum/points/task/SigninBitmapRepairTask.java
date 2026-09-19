package com.school.forum.points.task;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.points.config.PointsProperties;
import com.school.forum.points.entity.UserSignin;
import com.school.forum.points.mapper.UserSigninMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 签到位图重建任务：每天用 {@code t_user_signin} 重新覆盖最近两个月的位图。
 *
 * <p><b>为什么必须有这个任务：</b>位图是加速器，不是真相（ADR-007）。
 * 加速器丢失本身不可怕，可怕的是丢了之后没人知道——用户的「本月已签到 20 天」
 * 会突然变成 3 天，而他明明签满了。重建任务把「位图丢了」从一种
 * 需要人工介入的故障，变成一次最多滞后一天的自动恢复。
 *
 * <p>它是**幂等的、只写不删**的：数据库里有哪一天就置哪一位，
 * 因此重复执行、与签到请求并发执行都不会出错。位图里多出来的位
 * （数据库没有的）不会被清除——那意味着签到记录被删过，
 * 属于不该发生的事，交给对账查询去发现，而不是让一个后台任务悄悄抹掉证据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SigninBitmapRepairTask {

    /**
     * 每轮 pipeline 覆盖的用户数。
     *
     * <p>不一次提交全部：每个用户每月最多 31 条 {@code SETBIT}，
     * 若一次性把 10 万用户塞进一个 pipeline，Redis 侧会积压几百万条待执行命令
     * 并占用可观的内存。分组之后每轮几千条，随时可中断、可续跑。
     */
    private static final int USERS_PER_ROUND = 500;

    private final StringRedisTemplate redis;
    private final UserSigninMapper userSigninMapper;
    private final PointsProperties properties;
    private final RedissonClient redissonClient;

    /**
     * 调度时间做成配置项而不是字面量：默认仍是《04》7.3 节规定的每日 04:00，
     * 但能在不改代码的前提下挪开（避开备份窗口），也让它可被测试真正触发到——
     * 一个只会感知时间的任务如果时间不可控，就只能靠「上线后看日志」来验证。
     */
    @Scheduled(cron = "${forum.points.repair-cron:0 0 4 * * ?}")
    public void repair() {
        RLock lock = redissonClient.getLock(RedisKey.jobLock("points-signin-bitmap-repair"));
        boolean locked = false;
        try {
            // 抢不到就跳过：本任务不补跑也没关系（明天的覆盖同样完整），
            // 而多实例同时跑只会做重复的写入
            locked = lock.tryLock(0, 30, TimeUnit.MINUTES);
            if (!locked) {
                return;
            }
            doRepair();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 与月度结算任务同样的取舍：不向上抛，明天再来
            log.error("签到位图重建失败，将在下次调度时重试", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doRepair() {
        // 从「上个月 1 号」开始：位图的 TTL 是 70 天，覆盖当月与上月两个月份，
        // 因此只需要重建这段时间的数据。更早的位图已经过期，也不会有任何代码去读它
        LocalDate since = YearMonth.now().minusMonths(1).atDay(1);
        List<UserSignin> rows = userSigninMapper.selectSigninSince(since);
        if (rows.isEmpty()) {
            return;
        }

        // 按 (用户, 月份) 分组：一个位图键对应一个用户的一个月
        Map<String, Bitmap> grouped = group(rows);
        List<Bitmap> bitmaps = new ArrayList<>(grouped.values());

        int repaired = 0;
        int mismatch = 0;
        for (int start = 0; start < bitmaps.size(); start += USERS_PER_ROUND) {
            List<Bitmap> round = bitmaps.subList(start, Math.min(start + USERS_PER_ROUND, bitmaps.size()));
            List<Long> counts = writeRound(round);
            for (int i = 0; i < round.size(); i++) {
                repaired += round.get(i).days.size();
                if (i < counts.size() && counts.get(i) != round.get(i).days.size()) {
                    // 写完之后位数还对不上，说明这个键正被并发的签到请求修改
                    // （签到会 SETBIT 今天的位）。这不是故障，不计入告警
                    mismatch++;
                }
            }
        }

        log.info("签到位图重建完成：用户月份组数={}, 置位次数={}, 并发不一致组数={}",
                bitmaps.size(), repaired, mismatch);
    }

    /**
     * 写入一轮位图，返回每组写完后实际置位为 1 的位数。
     *
     * <p>命令放在**同一次 pipeline** 里按 {@code SETBIT → EXPIRE → BITCOUNT} 的顺序提交：
     * Redis 单线程顺序执行管道中的命令，因此末尾的 {@code BITCOUNT} 一定看得到
     * 本次写入。整轮只有一次网络往返，无论其中有多少条命令。
     *
     * <p>{@code EXPIRE} 是必须的：{@code SETBIT} 在一个已过期的键上会
     * **新建一个永不过期的键**，若不补上 TTL，重建出来的位图会永久驻留内存。
     */
    private List<Long> writeRound(List<Bitmap> round) {
        long ttlSeconds = Duration.ofDays(properties.getBitmapTtlDays()).toSeconds();

        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            RedisSerializer<String> serializer = RedisSerializer.string();
            for (Bitmap bitmap : round) {
                byte[] key = serializer.serialize(bitmap.key);
                for (Integer offset : bitmap.days) {
                    connection.stringCommands().setBit(key, offset, true);
                }
                connection.keyCommands().expire(key, ttlSeconds);
                connection.stringCommands().bitCount(key);
            }
            // 管道模式下命令只是被排进队列，真正的结果由 executePipelined 统一收集
            return null;
        });

        // 结果按命令提交顺序返回，每组的最后一条是 BITCOUNT，据此定位它的下标。
        // 下标靠累加得到而不是写死偏移量：一旦上面的命令序列变了，
        // 这里跟着变一次即可，不会出现「偏移量对不上却静默取错值」
        List<Long> counts = new ArrayList<>(round.size());
        int cursor = 0;
        for (Bitmap bitmap : round) {
            cursor += bitmap.days.size();   // SETBIT × n
            cursor += 1;                    // EXPIRE
            counts.add(cursor < results.size() ? toLong(results.get(cursor)) : 0L);
            cursor += 1;
        }
        return counts;
    }

    /** 按 {@code (userId, yearMonth)} 分组成位图键与待置位的偏移量集合 */
    private Map<String, Bitmap> group(List<UserSignin> rows) {
        Map<String, Bitmap> grouped = new LinkedHashMap<>();
        for (UserSignin row : rows) {
            if (row.getUserId() == null || row.getSigninDate() == null) {
                continue;
            }
            String yearMonth = row.getYearMonth() != null
                    ? row.getYearMonth()
                    : YearMonth.from(row.getSigninDate()).toString();
            String key = RedisKey.signinBitmap(row.getUserId(), yearMonth);
            // 偏移量 = 日 - 1，与 SigninServiceImpl 里的算法必须一致；
            // 两处算错任意一处，位图就会整体错位一天，而那种错误极难从界面上看出来
            grouped.computeIfAbsent(key, Bitmap::new)
                    .days.add(row.getSigninDate().getDayOfMonth() - 1);
        }
        return grouped;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 一个位图键及其应置位的偏移量。
     *
     * <p>私有内部类，字段直接暴露给外部类使用——它的生命周期不超出本类的两个方法，
     * 为它写 getter/setter 只是形式主义。换成 record 反而更差：
     * {@code days} 需要在创建后继续追加。
     */
    private static final class Bitmap {

        private final String key;
        private final List<Integer> days = new ArrayList<>();

        private Bitmap(String key) {
            this.key = key;
        }
    }
}
