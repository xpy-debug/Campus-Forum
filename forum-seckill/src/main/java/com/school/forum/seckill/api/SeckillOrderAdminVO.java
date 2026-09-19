package com.school.forum.seckill.api;

import com.school.forum.seckill.convert.SeckillConverter;
import com.school.forum.seckill.entity.SeckillOrder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理端看到的秒杀订单。
 *
 * <p>比用户侧多两样：<b>下单人是谁</b>（否则管理员不知道该给谁发货），
 * 以及<b>超时时间</b>（管理员需要判断「这单还剩多久会自动关闭」，
 * 从而决定是等它超时还是手动取消）。
 *
 * <p>{@code nickname} 由 {@code SeckillAdminApiImpl} 调 {@code UserApi} 批量补齐——
 * 秒杀域不查 {@code t_user}，这样用户表的结构不会被营销域锁死。
 */
public record SeckillOrderAdminVO(Long id,
                                  String orderNo,
                                  Long userId,
                                  String nickname,
                                  Long activityId,
                                  Long goodsId,
                                  String goodsName,
                                  Integer pointsCost,
                                  Integer status,
                                  String statusName,
                                  String expireTime,
                                  String payTime,
                                  String finishTime,
                                  String closeTime,
                                  String createTime) {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static SeckillOrderAdminVO of(SeckillOrder order, String nickname) {
        if (order == null) {
            return null;
        }
        return new SeckillOrderAdminVO(
                order.getId(), order.getOrderNo(), order.getUserId(), nickname,
                order.getActivityId(), order.getGoodsId(), order.getGoodsName(), order.getPointsCost(),
                order.getStatus(), SeckillConverter.orderStatusName(order.getStatus()),
                format(order.getExpireTime()), format(order.getPayTime()),
                format(order.getFinishTime()), format(order.getCloseTime()),
                format(order.getCreateTime()));
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }
}
