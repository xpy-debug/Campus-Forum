package com.school.forum.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分商城兑换订单，对应 {@code t_mall_order}。
 *
 * <p><b>状态机只有两条边，且都是单向的：</b>
 *
 * <pre>
 *   0 待发放 ──(管理员标记发放)──→ 1 已完成     —— 终态
 *        └────(用户取消 / 管理员取消)────→ 2 已取消   —— 终态
 * </pre>
 *
 * 没有「支付中」「退款中」这类中间态，因为商城是**即时扣减**：
 * 下单成功的那一刻积分就已经扣掉了，不存在「等用户付款」的阶段。
 * 这与秒杀订单（0待支付 → 1已支付 → 3超时关闭）刻意不同——
 * 多了中间态就要多一套超时扫描与补偿任务，而这里没有对应的业务需求。
 */
@Data
@TableName("t_mall_order")
public class MallOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 状态 ====================

    /** 待发放：积分已扣、库存已扣，等管理员发货 */
    public static final int STATUS_PENDING = 0;
    /** 已完成：管理员已发放。终态 */
    public static final int STATUS_FINISHED = 1;
    /** 已取消：积分与库存均已退回。终态 */
    public static final int STATUS_CANCELLED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外订单号 {@code M + yyyyMMdd + 6 位日内序号}，与自增主键解耦，不暴露兑换总量 */
    private String orderNo;

    private Long userId;

    private Long goodsId;

    /** 商品名称快照。商品改名后，订单页仍要显示兑换时的名字 */
    private String goodsName;

    /** 商品类型快照。商品类型被后台改过时，订单仍按当时的类型处理发放方式 */
    private Integer goodsType;

    /** 消耗积分快照。商品调价不影响已生成的订单 */
    private Integer pointsCost;

    private Integer status;

    private String remark;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否仍是「待发放」。这是用户唯一有权取消的状态 */
    public boolean cancellable() {
        return status != null && status == STATUS_PENDING;
    }
}
