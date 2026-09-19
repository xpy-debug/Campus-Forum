package com.school.forum.seckill.convert;

import com.school.forum.seckill.entity.SeckillActivity;
import com.school.forum.seckill.entity.SeckillOrder;
import com.school.forum.seckill.vo.SeckillActivityVO;
import com.school.forum.seckill.vo.SeckillOrderVO;

import java.time.LocalDateTime;

/**
 * 秒杀实体到出参的转换。
 *
 * <p>与 {@code MallConverter} 同一定位：把「跟当前用户有没有关系」的结论
 * （这里主要是 {@code grabbable} / {@code payable} / {@code cancellable}）
 * 在服务端算好。前端要自己凑齐这些条件，就得先知道状态机与时间窗的内部规则，
 * 那等于把业务规则复制到浏览器里，改一处漏一处的概率随之翻倍。
 */
public final class SeckillConverter {

    private SeckillConverter() {
    }

    public static SeckillActivityVO toActivityVO(SeckillActivity activity, LocalDateTime now) {
        if (activity == null) {
            return null;
        }
        int stock = value(activity.getAvailableStock());
        return new SeckillActivityVO(
                activity.getId(),
                activity.getName(),
                activity.getGoodsId(),
                activity.getGoodsName(),
                activity.getGoodsCover(),
                activity.getGoodsType(),
                value(activity.getPointsCost()),
                stock,
                value(activity.getTotalStock()),
                value(activity.getPerUserLimit()),
                activity.getStartTime(),
                activity.getEndTime(),
                activity.getStatus(),
                activityStatusName(activity.getStatus()),
                // 只是一致性检查，不承担判定职责：真正的「能不能抢」在 Lua 里
                activity.getStatus() != null
                        && activity.getStatus() == SeckillActivity.STATUS_RUNNING
                        && activity.inTimeWindow(now)
                        && stock > 0);
    }

    public static SeckillOrderVO toOrderVO(SeckillOrder order) {
        if (order == null) {
            return null;
        }
        return new SeckillOrderVO(
                order.getId(),
                order.getOrderNo(),
                order.getActivityId(),
                order.getGoodsName(),
                order.getGoodsType(),
                value(order.getPointsCost()),
                order.getStatus(),
                orderStatusName(order.getStatus()),
                order.getExpireTime(),
                order.getPayTime(),
                order.getFinishTime(),
                order.getCloseTime(),
                order.getCreateTime(),
                order.unpaid(),
                order.unpaid());
    }

    /**
     * 活动状态中文名。
     *
     * <p>直接读数据库的 {@code status}，不按时间窗现算：状态由每分钟的刷新任务维护，
     * 与时间窗的偏差最多一分钟。两处各算一次反而会打架——
     * 一旦「数据库说进行中、时间窗说已结束」，用户侧列表与详情页就会给出两种说法。
     * 判据只放在刷新任务里，其他地方一律读它的结果。
     */
    public static String activityStatusName(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case SeckillActivity.STATUS_NOT_STARTED -> "即将开始";
            case SeckillActivity.STATUS_RUNNING -> "进行中";
            case SeckillActivity.STATUS_ENDED -> "已结束";
            case SeckillActivity.STATUS_OFFLINE -> "已下线";
            default -> "未知状态";
        };
    }

    public static String orderStatusName(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case SeckillOrder.STATUS_UNPAID -> "待支付";
            case SeckillOrder.STATUS_PAID -> "待发放";
            case SeckillOrder.STATUS_CANCELLED -> "已取消";
            case SeckillOrder.STATUS_TIMEOUT -> "超时关闭";
            case SeckillOrder.STATUS_REFUNDED -> "已退款";
            case SeckillOrder.STATUS_FINISHED -> "已完成";
            default -> "未知状态";
        };
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
