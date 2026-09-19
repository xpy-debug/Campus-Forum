package com.school.forum.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀活动，对应 {@code t_seckill_activity}。
 *
 * <p><b>库存是三层模型，恒等式 {@code total = available + locked + sold} 在任何时刻都必须成立。</b>
 * 它是所有对账任务的校验基准，也是理解本模块的入口：
 *
 * <pre>
 *   available（可售） ──下单──→ locked（已下单未支付） ──支付──→ sold（已售）
 *          ↑                          │
 *          └──────超时 / 取消──────────┘
 * </pre>
 *
 * <p>Redis 里的 {@code forum:seckill:stock:{id}} 是它的前置加速层，抗住洪峰；
 * 但两者的口径不同：<b>Redis 剩余 == available_stock</b>。
 * {@code locked} 里的库存早已被 Redis 扣过，不属于「Redis 侧还能抢的量」，
 * 对账时不能计入——这是一个写错就会每次误报的坑。
 *
 * <p><b>商品资料是快照。</b>{@code goodsName/goodsCover/goodsType} 在创建活动时
 * 从商城商品复制进来。抢购是 P99 &lt; 50ms 的热路径，不该为了显示一个商品名去跨模块查库；
 * 商品改名之后，这场活动的历史订单也应当保持原样。
 */
@Data
@TableName("t_seckill_activity")
public class SeckillActivity implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 状态 ====================

    /** 未开始：时间窗还没到，用户可见但不可抢 */
    public static final int STATUS_NOT_STARTED = 0;
    /** 进行中 */
    public static final int STATUS_RUNNING = 1;
    /** 已结束 */
    public static final int STATUS_ENDED = 2;
    /** 已下线：管理员手动撤下，时间窗未到也不可见 */
    public static final int STATUS_OFFLINE = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的优惠券模板 ID。积分秒杀卖商品不发券，故可为 null */
    private Long couponId;

    /** 关联的商城商品 ID（t_mall_goods） */
    private Long goodsId;

    /** 商品名快照 */
    private String goodsName;

    /** 封面图快照 */
    private String goodsCover;

    /** 商品类型快照 1优惠券 2实物 3虚拟物品 */
    private Integer goodsType;

    /** 秒杀价（积分）。与商品的 pointsPrice 分开：打折多少由活动决定，不改商品本身 */
    private Integer pointsCost;

    private String name;

    /** 活动总库存。不变量，恒等式的基准 */
    private Integer totalStock;

    private Integer availableStock;

    /** 已下单未支付的库存 */
    private Integer lockedStock;

    /** 已支付售出的库存 */
    private Integer soldCount;

    /** 每人限购。当前为 1；>1 时需同步调整订单表的唯一索引，见类注释与《04》4.6 节 */
    private Integer perUserLimit;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 支付超时秒数。秒杀订单的 expireTime = 创建时间 + 本值 */
    private Integer payTimeoutSec;

    /** 开始前多少分钟预热 */
    private Integer warmupMinutes;

    private Integer status;

    /** 乐观锁，仅用于后台人工调整库存等低频写，不参与秒杀主链路 */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 当前是否在可抢购的时间窗内 */
    public boolean inTimeWindow(LocalDateTime now) {
        return startTime != null && endTime != null
                && !now.isBefore(startTime) && !now.isAfter(endTime);
    }

    /** 是否已经过了结束时间 */
    public boolean endedAt(LocalDateTime now) {
        return endTime != null && now.isAfter(endTime);
    }

    /**
     * 是否到了该预热的时候。
     *
     * <p>窗口是 {@code [startTime - warmupMinutes, endTime)}，而<b>不是</b>
     * {@code [startTime - warmupMinutes, startTime)}。刻意让右边界延伸到活动结束，
     * 是为了支持<b>补预热</b>：如果应用在活动开始前一秒才启动、或预热任务连续失败，
     * 一个「只预热未开始活动」的判定会让这场活动永远没有 Redis 库存，
     * 所有请求都被判为「未预热」而失败——而它其实完全可以救回来。
     *
     * <p>重复执行是安全的：实际写入用 {@code SETNX}，已扣减的库存不会被重置。
     */
    public boolean shouldWarmup(LocalDateTime now) {
        if (startTime == null || endTime == null || status == null || status == STATUS_OFFLINE) {
            return false;
        }
        int minutes = warmupMinutes == null ? 10 : warmupMinutes;
        return !now.isBefore(startTime.minusMinutes(minutes)) && now.isBefore(endTime);
    }

    /**
     * 状态是否需要按时间窗刷新。
     *
     * <p>由每分钟的状态刷新任务使用：只在「数据库里的状态」与「时间窗推导出的状态」
     * 不一致时才写库，避免每分钟对每场活动都做一次无意义的 UPDATE。
     */
    public int statusAt(LocalDateTime now) {
        if (status != null && status == STATUS_OFFLINE) {
            return STATUS_OFFLINE;
        }
        if (now.isBefore(startTime)) {
            return STATUS_NOT_STARTED;
        }
        return now.isBefore(endTime) ? STATUS_RUNNING : STATUS_ENDED;
    }

    /** 用户侧是否可见：未下线即可见——「已结束」的活动仍要能点进去看结果 */
    public boolean visible() {
        return status != null && status != STATUS_OFFLINE;
    }
}
