package com.school.forum.seckill.api;

import com.school.forum.seckill.convert.SeckillConverter;
import com.school.forum.seckill.entity.SeckillActivity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理端看到的秒杀活动。
 *
 * <p><b>比用户侧多的是「这场活动当前什么状态」</b>：三层库存的三个数字
 * （可售 / 锁定 / 已售）必须全都给出来，管理员才能回答「还剩多少能卖」
 * 与「有多少被未支付的订单占着」这两个不同的问题。
 * 用户侧只需要「还剩几件能抢」，多给反而让人以为能锁定库存。
 *
 * <p>时间格式化成字符串，理由与 {@code MallGoodsAdminVO} 一致：
 * 不让接口的输出依赖于全局 Jackson 配置。
 */
public record SeckillActivityAdminVO(Long id,
                                     String name,
                                     Long goodsId,
                                     String goodsName,
                                     String goodsCover,
                                     Integer goodsType,
                                     Integer pointsCost,
                                     Integer totalStock,
                                     Integer availableStock,
                                     Integer lockedStock,
                                     Integer soldCount,
                                     Integer perUserLimit,
                                     String startTime,
                                     String endTime,
                                     Integer payTimeoutSec,
                                     Integer warmupMinutes,
                                     Integer status,
                                     String statusName,
                                     String createTime,
                                     String updateTime) {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static SeckillActivityAdminVO of(SeckillActivity activity) {
        if (activity == null) {
            return null;
        }
        return new SeckillActivityAdminVO(
                activity.getId(), activity.getName(),
                activity.getGoodsId(), activity.getGoodsName(), activity.getGoodsCover(), activity.getGoodsType(),
                activity.getPointsCost(),
                activity.getTotalStock(), activity.getAvailableStock(),
                activity.getLockedStock(), activity.getSoldCount(),
                activity.getPerUserLimit(),
                format(activity.getStartTime()), format(activity.getEndTime()),
                activity.getPayTimeoutSec(), activity.getWarmupMinutes(),
                activity.getStatus(), SeckillConverter.activityStatusName(activity.getStatus()),
                format(activity.getCreateTime()), format(activity.getUpdateTime()));
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }
}
