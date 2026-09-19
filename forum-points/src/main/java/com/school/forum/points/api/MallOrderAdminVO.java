package com.school.forum.points.api;

import com.school.forum.points.entity.MallOrder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理端看到的兑换订单视图。
 *
 * <p>比用户侧多两样东西：<b>下单人是谁</b>（否则管理员不知道发给谁），
 * 以及<b>备注</b>（发放记录、取消原因）。用户侧看不到备注里的内部说明，
 * 所以两侧不能共用一个 VO。
 *
 * <p>用户名与昵称由 {@code forum-admin} 调 {@code UserApi} 补齐后再填进来——
 * 积分域不查 {@code t_user}。这也是本类把 {@code username}/{@code nickname}
 * 设计成可变字段的原因（用 record 就没法后续填充了）。
 */
public record MallOrderAdminVO(Long id,
                               String orderNo,
                               Long userId,
                               String nickname,
                               Long goodsId,
                               String goodsName,
                               Integer goodsType,
                               Integer pointsCost,
                               Integer status,
                               String remark,
                               String createTime,
                               String finishTime,
                               String cancelTime) {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static MallOrderAdminVO of(MallOrder order, String nickname) {
        if (order == null) {
            return null;
        }
        return new MallOrderAdminVO(
                order.getId(), order.getOrderNo(), order.getUserId(), nickname,
                order.getGoodsId(), order.getGoodsName(), order.getGoodsType(), order.getPointsCost(),
                order.getStatus(), order.getRemark(),
                format(order.getCreateTime()), format(order.getFinishTime()), format(order.getCancelTime()));
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }
}
