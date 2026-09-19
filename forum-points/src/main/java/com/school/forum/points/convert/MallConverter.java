package com.school.forum.points.convert;

import com.school.forum.points.entity.MallGoods;
import com.school.forum.points.entity.MallOrder;
import com.school.forum.points.vo.MallGoodsVO;
import com.school.forum.points.vo.MallOrderVO;

/**
 * 商城实体到出参的转换。
 *
 * <p>{@code canExchange} 的判定放在这里而不是前端：它同时依赖商品状态、库存
 * 与调用者余额，前端要凑齐这三个条件就得先知道「商品状态」这个内部概念。
 * 让服务端直接给结论，按钮的可用性就只有一处规则。
 */
public final class MallConverter {

    private MallConverter() {
    }

    /**
     * 商品（用户侧）。
     *
     * <p>{@code canExchange} 里带上了 {@code onSale()} 判断，即使调用方
     * （用户侧列表与详情）已经过滤过状态：这是一层冗余，但它让本方法
     * 对任何调用方都是安全的——少一个「必须传上架商品」的隐含前提，
     * 就少一次将来被误用的机会。这类「重复但无害」的防御性判断留在这里是划算的。
     *
     * @param userBalance 调用者当前余额。未登录场景传 0，于是所有商品都不可兑换
     */
    public static MallGoodsVO toGoodsVO(MallGoods goods, int userBalance) {
        if (goods == null) {
            return null;
        }
        int price = value(goods.getPointsPrice());
        int stock = value(goods.getStock());
        boolean affordable = userBalance >= price;
        boolean inStock = stock > 0;

        return new MallGoodsVO(
                goods.getId(),
                goods.getName(),
                goods.getCoverImage(),
                goods.getDescription(),
                goods.getType(),
                price,
                stock,
                value(goods.getSoldCount()),
                goods.onSale() && affordable && inStock,
                // 库存不足时「还差多少分」没有意义，返回 0 而不是负数或差价
                inStock && !affordable ? price - userBalance : 0);
    }

    /**
     * 兑换订单（用户侧）。
     *
     * <p>{@code cancellable} 直接问实体而不是在前端比 {@code status == 0}：
     * 「什么状态可以取消」是会变的业务规则（比如将来允许联系客服取消已发放订单），
     * 放在实体上就只有一处需要改。
     */
    public static MallOrderVO toOrderVO(MallOrder order) {
        if (order == null) {
            return null;
        }
        return new MallOrderVO(
                order.getId(),
                order.getOrderNo(),
                order.getGoodsId(),
                order.getGoodsName(),
                order.getGoodsType(),
                value(order.getPointsCost()),
                order.getStatus(),
                statusName(order.getStatus()),
                order.cancellable(),
                order.getCreateTime(),
                order.getFinishTime(),
                order.getCancelTime());
    }

    /**
     * 状态中文名。
     *
     * <p>与 {@code status} 一起返回，前端不必再维护一份映射表；
     * 兜底成「未知状态」而不是 null，保证界面永远有一句能读的话。
     */
    private static String statusName(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case MallOrder.STATUS_PENDING -> "待发放";
            case MallOrder.STATUS_FINISHED -> "已完成";
            case MallOrder.STATUS_CANCELLED -> "已取消";
            default -> "未知状态";
        };
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
