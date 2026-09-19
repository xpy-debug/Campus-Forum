package com.school.forum.points.service;

import com.school.forum.common.result.PageResult;
import com.school.forum.points.vo.PointsAccountVO;
import com.school.forum.points.vo.PointsRecordVO;

import java.time.LocalDate;

/**
 * 积分账户：余额、流水，以及**所有会改变余额的动作**。
 *
 * <p>本接口是积分资产的唯一出口。签到、月度奖励、商城兑换、取消退回四件事
 * 分散在三个不同的调用方（用户请求、定时任务、管理端），但它们改的是同一个
 * {@code balance} 列，遵循的是同一组不变式。把写入集中在这里，是为了让
 * 「余额怎么变的」这个问题的答案只有一份实现——每处各写一遍
 * {@code UPDATE balance} 的话，迟早会有人漏掉流水、漏掉 {@code balance_after}，
 * 或者用「先读再算再写」的方式把并发写坏。
 *
 * <p>所有写入方法都是**幂等或可判定的**：
 * <ul>
 *   <li>{@link #earnSignin} 依赖 {@code uk_user_date}，同一天第二次必然失败；</li>
 *   <li>{@link #earnMonthlyBonus} 依赖 {@code uk_user_biz}，同一月第二次必然失败；</li>
 *   <li>{@link #spendForOrder} 依赖 {@code WHERE balance >= ?}，扣不动就是余额不足。</li>
 * </ul>
 * 幂等靠的是数据库约束而不是 Redis 或进程内状态——后两者都会失效，
 * 唯一索引不会。
 */
public interface PointsAccountService {

    /** 账户概览。从未有过积分行为的用户返回全 0，而不是抛「账户不存在」 */
    PointsAccountVO account(Long userId);

    /**
     * 我的积分明细，按时间倒序游标分页。
     *
     * @param cursor 上一页的 {@code nextCursor}，首页传 null
     */
    PageResult<PointsRecordVO> records(Long userId, String cursor, int size);

    /**
     * 当前余额。不存在账户时返回 0。
     *
     * <p>这是**展示与快速失败**用的读，不承担并发安全职责：
     * 真正决定「能不能扣」的是 {@code deductBalance} 的条件更新。
     */
    int balanceOf(Long userId);

    /** 开户。已存在则什么都不做（{@code INSERT IGNORE}） */
    void initAccount(Long userId);

    /**
     * 记一次签到：写签到记录 + 加分 + 记流水，三件事在同一个事务里。
     *
     * <p><b>重复签到会抛 {@code DuplicateKeyException}</b>（来自
     * {@code uk_user_date}），而不是返回 0。这是有意的：
     * 抛异常才会让整个事务回滚，从而保证「签到行没写进去，积分也一定没加」。
     * 若改成「查到重复就返回 0」，加分那一步已经执行过了，就会变成
     * 「没签上到但白拿了积分」。
     *
     * <p>调用方（{@code SigninService}）必须捕获这个异常并当作
     * 「今天已签到」处理——那正是它要区分的正常状态。
     *
     * @param date 签到日期，由调用方传入而不是在这里取 {@code LocalDate.now()}：
     *             日期与年月冗余列必须由同一个值推导，两处各取一次
     *             {@code now()} 有可能跨零点错位
     * @return 本次获得的积分
     */
    int earnSignin(Long userId, LocalDate date);

    /**
     * 发放月度全勤奖励。
     *
     * <p>与 {@link #earnSignin} 同理，重复发放会抛
     * {@code DuplicateKeyException}（来自 {@code uk_user_biz}）。
     * 结算是**每天重跑上月**的，所以「已经发过」是常态而非异常，
     * 调用方（{@code settleSigninBonus}）必须捕获它并跳过该用户。
     *
     * @param yearMonth 结算月份 {@code yyyy-MM}
     * @return 本次发放的积分；流水已存在时返回 0
     */
    int earnMonthlyBonus(Long userId, String yearMonth);

    /**
     * 兑换扣分。
     *
     * <p>余额不足时抛 {@code POINTS_NOT_ENOUGH}（16002），由外层事务回滚整笔兑换。
     *
     * @param orderNo    订单号，作为流水的业务标识
     * @param goodsName  商品名，仅用于拼流水备注，让明细页能读出「换了什么」
     */
    void spendForOrder(Long userId, int points, String orderNo, String goodsName);

    /**
     * 取消兑换退回积分。
     *
     * <p>退回记为正数流水并计入 {@code totalEarned}，理由见
     * {@code UserPointsMapper.addBalance}：这样三条不变式同时成立，
     * 且「累计消耗过 100 分」这个事实不被退款抹掉。
     *
     * <p>本方法自身不做重复保护——重复取消由订单状态的条件更新挡住
     * （{@code markCancelled} 影响 0 行就整笔回滚），因此不会走到这里。
     */
    void refundOrder(Long userId, int points, String orderNo, String goodsName);

    /**
     * 秒杀支付扣分。
     *
     * <p>与 {@link #spendForOrder} 的唯一区别是流水类型（{@code biz_type=6}）。
     * 分开的理由见 {@code PointsRecord.BIZ_SECKILL_SPEND}。
     *
     * <p><b>为什么秒杀不像库存那样在 Redis 预扣：</b>见《02-架构设计》ADR-010。
     * 一句话——库存扣多了有回补路径、属于可自愈的一致性问题；
     * 积分是用户资产，预扣会凭空造出第二份「已扣积分」的状态，
     * 一旦与 {@code balance} 不一致，没有任何口径能自动判定该信哪一边。
     * 所以这条链路上 Redis 只预扣库存，积分在这里用条件更新扣。
     *
     * <p>余额不足时抛 16002，由外层事务回滚整笔支付——此时订单仍是「待支付」，
     * 到超时后由取消流程释放库存。**不需要为「积分不足」单独写一条补偿逻辑**，
     * 这正是把扣分放在支付而不是抢占时刻的收益。
     */
    void spendForSeckill(Long userId, int points, String orderNo, String goodsName);

    /**
     * 秒杀订单退回积分（管理员取消已支付的订单时调用）。
     *
     * <p>与 {@link #refundOrder} 同理：退回记为一笔 {@code biz_type=7} 的正数流水，
     * 计入 {@code totalEarned}，从而保住三条不变式（见
     * {@code UserPointsMapper.addBalance}）。
     *
     * <p><b>重复退回由调用方挡住</b>：秒杀订单的取消走 `WHERE status=?` 的条件更新，
     * 影响 0 行就整笔回滚，因此不会走到这里两次。
     */
    void refundSeckill(Long userId, int points, String orderNo, String goodsName);
}
