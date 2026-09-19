package com.school.forum.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动数据访问。
 *
 * <p>三个方法都是<b>条件 UPDATE</b>，构成库存三层模型的全部流转。
 * 它们的共同点是：把「判断」和「修改」压进一条语句，靠 InnoDB 的行锁保证
 * 判定与修改之间没有并发插入的缝隙。写成「先查出来判断，再更新回去」的话，
 * 两个请求会双双通过判断——那正是超卖的成因。
 *
 * <p>每个方法都要求调用方检查返回值：影响 0 行意味着**前提不成立了**
 * （库存不够、状态已变、数据漂移），而绝不能当作「更新了一条影响 0 行的记录」放过去。
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 下单扣减：{@code available → locked}。
     *
     * <p>由 MQ 消费者在落库事务里调用。条件是 {@code available_stock >= qty}——
     * 这是 <b>DB 层防超卖的最后一道防线</b>：如果 Redis 与 DB 发生漂移
     * （比如 Redis 被清空后重新预热、或消息重复投递），这一步会拦住它。
     *
     * @return 影响行数。0 表示 DB 库存不足（漂移），调用方应回补 Redis 并告警，
     *         而不是重试——重试多少次结果都一样
     */
    @Update("UPDATE t_seckill_activity "
            + "SET available_stock = available_stock - #{qty}, locked_stock = locked_stock + #{qty} "
            + "WHERE id = #{activityId} AND available_stock >= #{qty}")
    int deductStock(@Param("activityId") Long activityId, @Param("qty") int qty);

    /**
     * 支付成功：{@code locked → sold}。
     *
     * <p>条件 {@code locked_stock >= qty} 同时是一致性断言：
     * 锁定的库存不可能少于要售出的数量。若影响 0 行，说明库存三层模型已经不自洽，
     * 应当让支付事务回滚并告警——带着破损的库存数据继续跑，
     * 只会让后面的对账更难定位问题。
     */
    @Update("UPDATE t_seckill_activity "
            + "SET locked_stock = locked_stock - #{qty}, sold_count = sold_count + #{qty} "
            + "WHERE id = #{activityId} AND locked_stock >= #{qty}")
    int markSold(@Param("activityId") Long activityId, @Param("qty") int qty);

    /**
     * 取消 / 超时：{@code locked → available}。
     *
     * <p><b>这是库存回补的唯一入口</b>，同时也是回补幂等的保证：
     * 重复的取消请求会在订单状态那一步（{@code WHERE status = 0}）被挡住，
     * 根本走不到这里；即使走到了，{@code locked_stock >= qty} 也不会让
     * 同一件库存被回补两次。
     *
     * <p>不限制活动状态：活动已结束或已下线时同样要把锁定库存放回去，
     * 否则那件货就被永久冻结在 {@code locked_stock} 里，
     * 活动结束时 {@code locked} 归零的约定也就被破坏了。
     */
    @Update("UPDATE t_seckill_activity "
            + "SET locked_stock = locked_stock - #{qty}, available_stock = available_stock + #{qty} "
            + "WHERE id = #{activityId} AND locked_stock >= #{qty}")
    int rollbackLocked(@Param("activityId") Long activityId, @Param("qty") int qty);

    /**
     * 已支付订单被取消时的库存退回：{@code sold → available}。
     *
     * <p>与 {@link #rollbackLocked} 的区别在于从哪一层扣：未支付的订单占的是
     * {@code locked}，已支付的在支付那一刻就被转成了 {@code sold}。
     * 少写了这一步，取消已支付订单就会让那件货永久停在 {@code sold} 里，
     * 库存恒等式看起来还成立（总数没变），但「可售」再也回不来了。
     *
     * <p>条件 {@code sold_count >= qty} 是重复退款的守卫：同一张单不可能被退两次
     * （订单状态的条件更新在前面挡着），这里再断言一次，两层都不依赖对方的正确性。
     */
    @Update("UPDATE t_seckill_activity "
            + "SET sold_count = sold_count - #{qty}, available_stock = available_stock + #{qty} "
            + "WHERE id = #{activityId} AND sold_count >= #{qty}")
    int refundSold(@Param("activityId") Long activityId, @Param("qty") int qty);
}
