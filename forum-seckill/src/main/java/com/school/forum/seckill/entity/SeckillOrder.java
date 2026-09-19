package com.school.forum.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀订单，对应 {@code t_seckill_order}。
 *
 * <p><b>状态机（与积分商城的「即时完成」刻意不同）：</b>
 *
 * <pre>
 *   0 待支付 ──支付成功──→ 1 已支付(待发放) ──管理员发放──→ 5 已完成
 *      │
 *      ├── 用户取消 ──→ 2 已取消      （积分从未被扣过，只需回补库存）
 *      └── 15 分钟超时 → 3 超时关闭    （同一套回补逻辑）
 * </pre>
 *
 * <p>多了「待支付」这个中间态，就要多一套超时扫描与回补任务——
 * 这是为「秒杀场景下用户需要一点时间确认」付出的代价，也是它与商城兑换的分界线。
 *
 * <p><b>积分在支付时才扣，不是抢购时。</b>于是取消/超时路径<em>不需要</em>退积分，
 * 少一条退款链路就少一类不一致。积分不足只会让支付失败，订单留在待支付，
 * 到点由超时流程收拾残局。
 *
 * <p>{@code uk_user_activity} 唯一索引是防重复下单的最终兜底；
 * 而「取消后不能重抢」这条产品规则也由它保证——取消的订单行仍在，
 * 第二次抢购在数据库层就插不进去。
 */
@Data
@TableName("t_seckill_order")
public class SeckillOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 状态 ====================

    /** 待支付。唯一可以支付、可以取消的状态 */
    public static final int STATUS_UNPAID = 0;
    /** 已支付，待管理员发放 */
    public static final int STATUS_PAID = 1;
    /** 已取消（用户或管理员主动） */
    public static final int STATUS_CANCELLED = 2;
    /** 超时关闭（15 分钟未支付，由延迟消息或定时扫描触发） */
    public static final int STATUS_TIMEOUT = 3;
    /** 已退款（预留，当前无写入方） */
    public static final int STATUS_REFUNDED = 4;
    /** 已完成（管理员已发放） */
    public static final int STATUS_FINISHED = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号 {@code S + yyyyMMdd + 6 位日内序号}，在抢购的 Lua 脚本里生成 */
    private String orderNo;

    private Long userId;

    private Long activityId;

    private Long goodsId;

    /** 商品名快照。订单是历史凭证，商品改名后仍显示当时的样子 */
    private String goodsName;

    /** 商品类型快照。活动被改成别的商品时，这张订单仍按当时的类型发放 */
    private Integer goodsType;

    /** 本单消耗的积分。积分秒杀用积分支付，不走 payAmount */
    private Integer pointsCost;

    private Long couponId;

    private Long userCouponId;

    private Integer quantity;

    private Integer orderAmount;

    private Integer status;

    /** 支付截止时间 = 创建时间 + 活动的 payTimeoutSec */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime finishTime;

    private LocalDateTime closeTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否仍是待支付。这是用户唯一有权取消、也是唯一能支付的状态 */
    public boolean unpaid() {
        return status != null && status == STATUS_UNPAID;
    }

    /** 是否已支付（含已发放、已完成）——这些状态下的取消需要退积分 */
    public boolean paid() {
        return status != null && (status == STATUS_PAID || status == STATUS_FINISHED);
    }

    /** 是否已支付且尚未发放，管理员可以标记完成 */
    public boolean finishable() {
        return status != null && status == STATUS_PAID;
    }
}
