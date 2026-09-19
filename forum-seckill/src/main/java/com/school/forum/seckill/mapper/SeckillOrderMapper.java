package com.school.forum.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀订单数据访问。
 *
 * <p>状态流转全部用<b>条件 UPDATE</b>，每个状态变更都带 {@code AND status = 0}
 * （或当前的期望状态）。这不是为了「防并发」这一件事，而是为了让**状态机本身**
 * 成为并发闸门：支付与取消同时发生、两次取消同时发生，
 * 都只有一个能改到行，另一个影响 0 行并据此直接返回。
 *
 * <p>于是取消流程不需要任何显式锁，也不需要先读一次判断——
 * 而那正是「重复退款」最常见的成因。
 */
@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 待支付 → 已支付。
     *
     * <p>带 {@code user_id} 条件：支付接口只认「本人的订单」。
     * 用 15012（订单不存在）而不是 10003 来回应「别人的订单」——
     * 前者不泄露「这个订单号存在」这一事实。
     *
     * @return 影响行数。0 表示订单不存在 / 不是本人 / 已不是待支付，调用方返回 15013
     */
    @Update("UPDATE t_seckill_order SET status = 1, pay_time = NOW() "
            + "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = 0")
    int markPaid(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    /**
     * 待支付 → 已取消 / 超时关闭。
     *
     * <p><b>这是取消流程的闸门。</b>影响 0 行就立刻返回，绝不执行后面的
     * 「回补库存 + 回补 Redis」——重复取消之所以不会重复回补，
     * 靠的就是这里而不是任何判断代码。
     *
     * @param status {@link SeckillOrder#STATUS_CANCELLED} 或 {@link SeckillOrder#STATUS_TIMEOUT}
     */
    @Update("UPDATE t_seckill_order SET status = #{status}, close_time = NOW() "
            + "WHERE order_no = #{orderNo} AND status = 0")
    int markClosed(@Param("orderNo") String orderNo, @Param("status") int status);

    /**
     * 已支付 → 已完成（管理员发放）。
     *
     * @return 影响行数。0 表示订单不是「已支付待发放」，调用方返回 15013
     */
    @Update("UPDATE t_seckill_order SET status = 5, finish_time = NOW() "
            + "WHERE order_no = #{orderNo} AND status = 1")
    int markFinished(@Param("orderNo") String orderNo);

    /**
     * 已支付（或已完成）→ 已退款。管理员取消一张已经付过积分的订单。
     *
     * <p>与 {@link #markClosed} 分开：那个只管「待支付 → 关闭」，
     * 走到它就意味着积分从未被扣过，因此不需要退分；
     * 而走到这里意味着积分已经扣了，<b>必须退分</b>，两者的事务内容完全不同。
     * 把两条路合成一个方法，就会出现「退分与否取决于调用方记不记得」这种隐患。
     *
     * <p>允许 {@code status IN (1,5)}：已发放的订单也可能需要撤回（实物发不出、活动被叫停）。
     *
     * @return 影响行数。0 表示订单不是已支付状态，或已经被退过款
     */
    @Update("UPDATE t_seckill_order SET status = 4, close_time = NOW() "
            + "WHERE order_no = #{orderNo} AND status IN (1, 5)")
    int markRefunded(@Param("orderNo") String orderNo);

    /**
     * 超时订单扫描（兜底路径）。
     *
     * <p>走 {@code idx_status_expire (status, expire_time)}：状态是等值、
     * 时间是范围，索引能直接定位到「最早的若干笔待支付且已过期」的订单，
     * 不需要扫描全部未支付订单。
     *
     * <p>为什么需要它：延迟消息在 Kafka 侧依赖 outbox 调度器，
     * 可能积压甚至丢失。只靠延迟消息，「库存被未支付订单占着」会持续到
     * 调度器恢复为止；有了这条兜底，最多晚 5 分钟。
     */
    @Select("SELECT * FROM t_seckill_order "
            + "WHERE status = 0 AND expire_time < #{now} "
            + "ORDER BY expire_time LIMIT #{limit}")
    List<SeckillOrder> selectTimeout(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 我的秒杀订单（倒序游标分页）。
     *
     * <p>放在 XML 里是因为带可选的游标条件；游标用 {@code id} 而不是时间戳，
     * 理由与商城订单一致（同秒多单会重复或漏行）。不按状态过滤——
     * 被取消、超时的订单同样是用户需要看到的历史。
     *
     * @param cursor 上一页最后一条的 ID，null 表示第一页
     * @param limit  调用方传 size + 1
     */
    List<SeckillOrder> selectByCursor(@Param("userId") Long userId,
                                      @Param("cursor") Long cursor,
                                      @Param("limit") int limit);
}
