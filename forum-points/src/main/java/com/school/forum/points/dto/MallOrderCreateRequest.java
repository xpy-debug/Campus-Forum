package com.school.forum.points.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 兑换请求。
 *
 * <p><b>只有商品 ID，没有积分、没有库存、没有价格。</b>这三样都必须由服务端
 * 从商品行读出——若让前端传「我要花 100 分换这个」，改一下请求体就能用 1 分换走
 * 价值 100 分的商品。这类「客户端提交的金额」是最经典的越权入口。
 *
 * <p>{@code remark} 是用户侧的留言（例如实物需要写收货信息），
 * 与服务端写的发放备注共用 {@code t_mall_order.remark} 一列——
 * 两者都是「关于这张订单的补充说明」，没有必要分成两列。
 */
@Data
public class MallOrderCreateRequest {

    @NotNull(message = "商品不能为空")
    private Long goodsId;

    /**
     * 兑换留言/收货信息。
     *
     * <p>限长 255 与列宽一致：让校验在进数据库之前失败，
     * 而不是等 MySQL 抛「Data too long」——后者的报错对用户毫无意义。
     */
    @Size(max = 255, message = "留言不能超过 255 字")
    private String remark;
}
