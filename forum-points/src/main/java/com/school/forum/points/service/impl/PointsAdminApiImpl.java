package com.school.forum.points.service.impl;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.points.api.PointsAdminApi;
import com.school.forum.points.config.PointsProperties;
import com.school.forum.points.mapper.UserSigninMapper;
import com.school.forum.points.service.PointsAccountService;
import com.school.forum.points.support.SigninCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 积分管理端能力的实现：月度全勤奖励结算。
 *
 * <p><b>为什么结算是「每天跑上月」而不是「月底跑当月」：</b>
 * 月底那一次如果因为重启、发版、机器故障没跑成，整月的奖励就漏了，
 * 而漏发的补救措施只能靠人去发现——用户不会为了 100 积分来找客服。
 * 改成每天重跑上月之后，任何一天的失败都会在第二天被自动补上，
 * 23:59 与 00:01 的边界问题也随之消失：结算的永远是已经结束的月份。
 *
 * <p>幂等由两层保证：{@code t_points_record} 的 {@code uk_user_biz}
 * （同一用户同一月只能记一次账）与结算前的流水查询（快路径）。
 * 因此这个任务重跑多少次都不会多发一分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsAdminApiImpl implements PointsAdminApi {

    /** 结算锁的持有时间。到期自动释放，即使节点崩了也不会永久卡住后续的结算 */
    private static final long LOCK_LEASE_MINUTES = 5L;

    private final UserSigninMapper userSigninMapper;
    private final PointsAccountService accountService;
    private final PointsProperties properties;
    private final RedissonClient redissonClient;

    @Override
    public int settleSigninBonus(String yearMonth) {
        YearMonth month = parseMonth(yearMonth);

        // 当月与未来月份不结算：那些月份的签到数据还没定型，
        // 现在算出来的「全勤名单」明天就会变，发出去的奖励也收不回来
        if (!month.isBefore(YearMonth.now())) {
            log.info("跳过签到奖励结算：{} 尚未结束", yearMonth);
            return 0;
        }

        RLock lock = redissonClient.getLock(RedisKey.pointsBonusLock(yearMonth));
        boolean locked = false;
        try {
            // 等待时间 0：抢不到就跳过本轮，不排队。多实例部署时只有第一个实例会真正结算，
            // 而没抢到锁的实例什么都不做也是对的——任务明天还会跑
            locked = lock.tryLock(0, LOCK_LEASE_MINUTES, TimeUnit.MINUTES);
            if (!locked) {
                log.info("签到奖励结算已在执行中，跳过本轮。month={}", yearMonth);
                return 0;
            }
            return settle(yearMonth);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 分批结算。
     *
     * <p>本方法**不加事务**：每个用户的发放各自是一个事务（在
     * {@code PointsAccountService.earnMonthlyBonus} 上）。这是有意的——
     * 一个事务里放几千个用户的加分，会长时间持有大量行锁，
     * 而且任何一个用户的异常都会让所有人的奖励一起回滚。
     * 分批 + 每人一事务的代价是「部分成功」成为可能，
     * 但那没关系：任务明天会重跑，已经发过的会被幂等挡掉，未发的会被补上。
     */
    private int settle(String yearMonth) {
        int threshold = properties.getBonusThreshold();
        int batchSize = properties.getBonusBatchSize();

        int settledUsers = 0;
        int settledPoints = 0;
        Long lastUserId = null;
        while (true) {
            List<SigninCount> batch = userSigninMapper.selectFullAttendanceUsers(
                    yearMonth, threshold, lastUserId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (SigninCount count : batch) {
                int points = settleOne(count.getUserId(), yearMonth);
                if (points > 0) {
                    settledUsers++;
                    settledPoints += points;
                }
            }
            lastUserId = batch.get(batch.size() - 1).getUserId();
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (settledUsers > 0) {
            log.info("签到奖励结算完成：month={}, 发放用户数={}, 共发放积分={}",
                    yearMonth, settledUsers, settledPoints);
        }
        return settledUsers;
    }

    /**
     * 给单个用户发放。
     *
     * <p>三种结局都算正常：
     * <ul>
     *   <li>发放成功 → 返回本次发出的积分数（正数）；</li>
     *   <li>流水已存在（快路径查到，返回 0）→ 说明之前发过了；</li>
     *   <li>唯一索引冲突（并发下两个实例同时查到「没发过」）→ 异常回滚，
     *       那个用户这一次的账不会被记两次，下一个实例会拿到异常并跳过。</li>
     * </ul>
     * 只有第四种情况需要记录：真正的异常（数据库连不上等）。
     * 它被限制在单个用户范围内，不会中断整批。
     *
     * <p>返回值是「发了多少分」而不是「发了几个人」——调用方靠它是否为正
     * 来区分「这次真的发了」与「早就发过了」。若直接把它当人数累加，
     * 得到的是积分总额，日志与接口返回值都会说谎。
     */
    private int settleOne(Long userId, String yearMonth) {
        try {
            return accountService.earnMonthlyBonus(userId, yearMonth);
        } catch (DuplicateKeyException e) {
            log.debug("全勤奖励已发放，跳过。userId={}, month={}", userId, yearMonth);
            return 0;
        } catch (Exception e) {
            // 单个用户失败不影响其他人：任务明天重跑时会把它补上
            log.error("全勤奖励发放失败，将在下次结算时重试。userId={}, month={}", userId, yearMonth, e);
            return 0;
        }
    }

    private YearMonth parseMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }
}
