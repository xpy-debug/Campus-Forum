package com.school.forum.points.vo;

import java.time.LocalDateTime;

/**
 * 用户侧看到的兑换订单。
 *
 * <p>商品名与积分消耗都取自订单行内的**快照列**，而不是现查商品表：
 * 商品改名或调价之后，用户看到的仍应是「我当时换了什么、花了多少」。
 * 若这里去 join {@code t_mall_goods}，历史订单会随商品的每次修改而变化，
 * 那就不再是一张凭证了。
 *
 * <p>不含 {@code remark}：备注是管理员侧的发放记录与取消原因，
 * 属于内部信息。用户需要知道「为什么取消了」时，应当由运营另行通知，
 * 而不是把内部备注直接暴露出去。
 *
 * @param id          订单 ID
 * @param orderNo     订单号，客服凭它定位
 * @param goodsId     商品 ID。商品可能已下架，前端点击时需容错
 * @param goodsName   商品名快照
 * @param goodsType   商品类型快照
 * @param pointsCost  消耗积分快照
 * @param status      0待发放 1已完成 2已取消
 * @param statusName  状态中文名，与 {@code status} 一起返回便于前端直接渲染
 * @param cancellable 是否可取消。由后端判定而不是前端按 status 猜，规则只有一处
 * @param createTime  兑换时间
 * @param finishTime  发放时间，未完成时为 null
 * @param cancelTime  取消时间，未取消时为 null
 */
public record MallOrderVO(Long id,
                          String orderNo,
                          Long goodsId,
                          String goodsName,
                          Integer goodsType,
                          Integer pointsCost,
                          Integer status,
                          String statusName,
                          boolean cancellable,
                          LocalDateTime createTime,
                          LocalDateTime finishTime,
                          LocalDateTime cancelTime) {
}
