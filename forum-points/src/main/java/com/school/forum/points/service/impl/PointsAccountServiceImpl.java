package com.school.forum.points.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.points.config.PointsProperties;
import com.school.forum.points.convert.PointsConverter;
import com.school.forum.points.entity.PointsRecord;
import com.school.forum.points.entity.UserPoints;
import com.school.forum.points.entity.UserSignin;
import com.school.forum.points.mapper.PointsRecordMapper;
import com.school.forum.points.mapper.UserPointsMapper;
import com.school.forum.points.mapper.UserSigninMapper;
import com.school.forum.points.service.PointsAccountService;
import com.school.forum.points.support.PageSizes;
import com.school.forum.points.vo.PointsAccountVO;
import com.school.forum.points.vo.PointsRecordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 积分账户实现。
 *
 * <p>本类里每一个写方法都对应一个完整的业务动作，且都是**一个事务**：
 * 改余额与写流水必须同时成功。只改余额不写流水，账就再也解释不清；
 * 只写流水不改余额，余额就是错的。
 *
 * <p>所有余额变更都走条件 / 增量更新，没有一处「先读后写」——
 * 唯一一次读取是写完之后取 {@code balance_after} 快照，
 * 那时行锁在本事务手里，读到的一定是自己刚写下的值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsAccountServiceImpl implements PointsAccountService {

    private final UserPointsMapper userPointsMapper;
    private final PointsRecordMapper pointsRecordMapper;
    private final UserSigninMapper userSigninMapper;
    private final PointsProperties properties;

    // ==================== 查询 ====================

    @Override
    public PointsAccountVO account(Long userId) {
        return PointsConverter.toAccountVO(selectAccount(userId));
    }

    @Override
    public PageResult<PointsRecordVO> records(Long userId, String cursor, int size) {
        int safeSize = PageSizes.clamp(size);
        // 多查一条用于判断有没有下一页，因此不需要额外的 COUNT
        List<PointsRecord> rows = pointsRecordMapper.selectByCursor(userId, parseCursor(cursor), safeSize + 1);
        return PageResult.ofCursor(rows, safeSize, r -> String.valueOf(r.getId()))
                .map(PointsConverter::toRecordVO);
    }

    @Override
    public int balanceOf(Long userId) {
        Integer balance = userPointsMapper.selectBalance(userId);
        return balance == null ? 0 : balance;
    }

    // ==================== 写入 ====================

    @Override
    public void initAccount(Long userId) {
        userPointsMapper.initAccount(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int earnSignin(Long userId, LocalDate date) {
        initAccount(userId);

        UserSignin signin = new UserSignin();
        signin.setUserId(userId);
        signin.setSigninDate(date);
        // 年月与日期由同一个 date 推导，不各自取一次当前时间：跨零点时两者会错位，
        // 而那会导致月度统计把这一天算进错误的月份
        signin.setYearMonth(YearMonth.from(date).toString());
        signin.setPoints(properties.getSigninPoints());
        // 唯一索引 uk_user_date 在这里生效。冲突时抛 DuplicateKeyException，
        // 整个事务回滚——今天的积分不会再加一次
        userSigninMapper.insert(signin);

        return earn(userId, properties.getSigninPoints(),
                PointsRecord.BIZ_SIGNIN, date.toString(), "每日签到");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int earnMonthlyBonus(Long userId, String yearMonth) {
        initAccount(userId);
        return earn(userId, properties.getBonusPoints(), PointsRecord.BIZ_MONTHLY_BONUS,
                yearMonth, yearMonth + " 全勤奖励");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void spendForOrder(Long userId, int points, String orderNo, String goodsName) {
        initAccount(userId);

        // 条件更新：余额不足时影响 0 行。扣成负数在这里是不可能的——
        // 判定与扣减在数据库的同一把行锁内完成，中间没有给并发留任何缝隙
        if (userPointsMapper.deductBalance(userId, points) == 0) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH);
        }
        insertRecord(userId, -points,
                PointsRecord.BIZ_MALL_EXCHANGE, orderNo, "兑换：" + goodsName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(Long userId, int points, String orderNo, String goodsName) {
        if (points <= 0) {
            // 历史数据或配置异常导致的 0 分订单：退回 0 分会在流水里留下一笔无意义的记录
            return;
        }
        // 退回也是「获得」，走同一个方法，理由见 UserPointsMapper.addBalance
        requireUpdated(userPointsMapper.addBalance(userId, points), "退回积分");
        insertRecord(userId, points,
                PointsRecord.BIZ_MALL_REFUND, orderNo, "取消兑换退回：" + goodsName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void spendForSeckill(Long userId, int points, String orderNo, String goodsName) {
        initAccount(userId);

        // 与商城兑换同一把武器：条件更新。抢购阶段不碰积分，
        // 所以这里是整条秒杀链路上唯一会扣积分的地方
        if (userPointsMapper.deductBalance(userId, points) == 0) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH);
        }
        insertRecord(userId, -points,
                PointsRecord.BIZ_SECKILL_SPEND, orderNo, "秒杀：" + goodsName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundSeckill(Long userId, int points, String orderNo, String goodsName) {
        if (points <= 0) {
            return;
        }
        requireUpdated(userPointsMapper.addBalance(userId, points), "退回秒杀积分");
        insertRecord(userId, points,
                PointsRecord.BIZ_SECKILL_REFUND, orderNo, "取消秒杀退回：" + goodsName);
    }

    // ==================== 内部方法 ====================

    /**
     * 加分并记流水，返回实际加的分数。
     *
     * <p>先查一次流水作为**快路径**：绝大多数重复调用（任务重跑、用户连点）
     * 都会在这里被挡掉，从而避免一次注定失败的唯一索引冲突。
     * 快路径挡不住的并发（两个实例同时查到「没记过」），由 {@code uk_user_biz}
     * 兜底——那时异常会向上抛出并回滚整个事务，结果同样是「只记一次账」。
     *
     * <p>这个分工很重要：查流水是**优化**，唯一索引才是**保证**。
     * 反过来依赖查询结果做最终判断，就会在并发下重复发放。
     */
    private int earn(Long userId, int points, int bizType, String bizId, String remark) {
        if (pointsRecordMapper.countByBiz(userId, bizType, bizId) > 0) {
            return 0;
        }
        requireUpdated(userPointsMapper.addBalance(userId, points), remark);
        insertRecord(userId, points, bizType, bizId, remark);
        return points;
    }

    /**
     * 写一条流水。
     *
     * <p>{@code balanceAfter} 必须在加分 / 扣分**之后**再查（见
     * {@code UserPointsMapper.selectBalance}）：只有在更新过的行上读取，
     * 才能拿到含本次变动的准确余额。
     */
    private void insertRecord(Long userId, int changeAmount, int bizType, String bizId, String remark) {
        Integer balanceAfter = userPointsMapper.selectBalance(userId);
        if (balanceAfter == null) {
            // 走到这里说明账户行在本次事务里刚被创建又读不到，属于不该发生的状态
            throw new IllegalStateException("积分账户不存在，userId=" + userId);
        }

        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setChangeAmount(changeAmount);
        record.setBalanceAfter(balanceAfter);
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setRemark(remark);
        pointsRecordMapper.insert(record);
    }

    private UserPoints selectAccount(Long userId) {
        return userPointsMapper.selectOne(
                Wrappers.<UserPoints>lambdaQuery().eq(UserPoints::getUserId, userId));
    }

    /**
     * 更新的行数必须是 1。
     *
     * <p>0 行的唯一合理解释是账户行不存在——而每个写入口都先调用了
     * {@code initAccount}，所以那意味着数据出了问题。此时抛异常让事务回滚，
     * 比默默返回一个「成功」的表面结果要好：后者会写出一条流水，
     * 却没有对应的余额变动，账就永久错了。
     */
    private void requireUpdated(int affected, String action) {
        if (affected != 1) {
            throw new IllegalStateException(action + "失败：积分账户行不存在或状态异常，affected=" + affected);
        }
    }

    /**
     * 解析游标。
     *
     * <p>非法游标**退回第一页**而不是报错：游标是服务端生成的，用户拿不到非法值；
     * 真出现了也只能是前端手工拼错了 URL 或旧链接失效。
     * 让这类情况看到第一页，比让用户对着一个 10001 参数错误发呆更有用。
     */
    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException e) {
            log.warn("积分流水游标非法，回退到第一页。cursor={}", cursor);
            return null;
        }
    }
}
