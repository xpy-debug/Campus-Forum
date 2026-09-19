package com.school.forum.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分流水，对应 {@code t_points_record}。
 *
 * <p><b>不可变表：只 INSERT，从不 UPDATE 或 DELETE。</b>
 * 一笔流水记错了，正确的做法是补一笔反向流水，而不是改掉原来那笔——
 * 后者会让「账户余额凭什么等于这个数」失去可追溯性，
 * 而积分是和用户资产直接挂钩的数据，必须能逐笔解释。
 *
 * <p><b>{@code uk_user_biz} 是本表存在的核心理由</b>，不是顺手加的约束：
 * 它是「同一笔业务只能记一次账」的最后防线。Redis 去重会失效（重启、误删），
 * 条件更新在特定时序下也可能被重复执行，唯一索引不会失效。
 */
@Data
@TableName("t_points_record")
public class PointsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 业务类型 ====================

    /** 每日签到 */
    public static final int BIZ_SIGNIN = 1;
    /** 月度全勤奖励 */
    public static final int BIZ_MONTHLY_BONUS = 2;
    /** 商城兑换（负数流水） */
    public static final int BIZ_MALL_EXCHANGE = 3;
    /** 兑换取消退回（正数流水） */
    public static final int BIZ_MALL_REFUND = 4;
    /** 管理员调整（预留，当前无写入方） */
    public static final int BIZ_ADMIN_ADJUST = 5;
    /**
     * 秒杀消耗（负数流水）。
     *
     * <p><b>为什么不复用 {@link #BIZ_MALL_EXCHANGE}：</b>两者的 {@code biz_id}
     * 都是订单号，但订单号来自不同的表（{@code t_seckill_order} / {@code t_mall_order}）。
     * 复用会让《04-数据库设计》§9 那条「每笔消耗都能查到对应订单」的校验
     * 无法判断该去查哪张表。秒杀与商城本就是两条独立链路（ADR-010）。
     */
    public static final int BIZ_SECKILL_SPEND = 6;
    /** 秒杀退回（正数流水）。当前只在管理员取消秒杀订单时产生 */
    public static final int BIZ_SECKILL_REFUND = 7;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 积分变动量，正为获得、负为消耗。用有符号 int，不能用无符号 */
    private Integer changeAmount;

    /** 变动后余额快照。它不参与计算，只用于排查「从哪一笔开始不对」 */
    private Integer balanceAfter;

    private Integer bizType;

    /**
     * 业务标识。签到是 {@code yyyy-MM-dd}，月度奖励是 {@code yyyy-MM}，
     * 兑换与退回是 {@code order_no}。
     *
     * <p>用业务标识而不是外键 ID，是为了让「没有对应业务行」的类型
     * （月度奖励不是任何一条记录触发的）也能统一表达，不必为它单独造一张表。
     */
    private String bizId;

    private String remark;

    private LocalDateTime createTime;
}
