package com.school.forum.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商城商品，对应 {@code t_mall_goods}。
 *
 * <p><b>只有管理员能改本表。</b>用户侧的全部接口都是只读的，
 * 下单走的是 {@code t_mall_order} 与两个条件更新（扣库存、扣积分），
 * 不直接写商品行——「改商品」与「兑换商品」在权限上是两件事，
 * 前者是运营行为，后者是消费行为。
 */
@Data
@TableName("t_mall_goods")
public class MallGoods implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 状态 ====================

    /** 草稿：后台可见，用户侧不可见 */
    public static final int STATUS_DRAFT = 0;
    /** 上架：用户侧可见且可兑换 */
    public static final int STATUS_ON_SALE = 1;
    /** 下架：后台可见，用户侧不可见。历史订单不受影响 */
    public static final int STATUS_OFF_SALE = 2;

    // ==================== 类型 ====================

    /** 优惠券类（发放券码） */
    public static final int TYPE_COUPON = 1;
    /** 实物类（需要线下领取或邮寄） */
    public static final int TYPE_PHYSICAL = 2;
    /** 虚拟物品（资料包、预约名额等） */
    public static final int TYPE_VIRTUAL = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String coverImage;

    private String description;

    private Integer type;

    /** 兑换所需积分。改价不影响已生成的订单（订单里有 points_cost 快照） */
    private Integer pointsPrice;

    private Integer stock;

    private Integer soldCount;

    private Integer status;

    /** 排序权重，越大越靠前 */
    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 用户侧是否可见。判据是状态，不是库存——售罄的商品仍要在列表里显示「已兑完」 */
    public boolean onSale() {
        return status != null && status == STATUS_ON_SALE;
    }
}
