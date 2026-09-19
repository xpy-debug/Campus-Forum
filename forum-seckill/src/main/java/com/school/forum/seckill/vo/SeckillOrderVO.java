package com.school.forum.seckill.vo;

import java.time.LocalDateTime;

/**
 * 用户侧看到的秒杀订单。
 *
 * <p>{@code payable} 与 {@code cancellable} 由服务端算好，前端不自己比
 * {@code status == 0}：什么状态能支付、能取消是业务规则，将来变了（比如允许
 * 超时后 5 分钟内的补支付）应当只改一处。这与 {@code MallConverter} 里
 * {@code cancellable} 的处理是同一个理由。
 *
 * @param id          订单 ID
 * @param orderNo     订单号
 * @param activityId  活动 ID
 * @param goodsName   商品名（下单时的快照）
 * @param goodsType   1优惠券 2实物 3虚拟物品
 * @param pointsCost  消耗积分
 * @param status      0待支付 1已支付(待发放) 2已取消 3超时关闭 4已退款 5已完成
 * @param statusName  状态中文名，前端不再维护映射表
 * @param expireTime  支付截止时间。前端据此显示倒计时
 * @param payTime     支付时间
 * @param finishTime  发放时间
 * @param closeTime   关闭时间（取消或超时）
 * @param createTime  下单时间
 * @param payable     是否可支付
 * @param cancellable 是否可取消
 */
public record SeckillOrderVO(Long id,
                             String orderNo,
                             Long activityId,
                             String goodsName,
                             Integer goodsType,
                             Integer pointsCost,
                             Integer status,
                             String statusName,
                             LocalDateTime expireTime,
                             LocalDateTime payTime,
                             LocalDateTime finishTime,
                             LocalDateTime closeTime,
                             LocalDateTime createTime,
                             boolean payable,
                             boolean cancellable) {
}
