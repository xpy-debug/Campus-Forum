package com.school.forum.seckill.vo;

import java.time.LocalDateTime;

/**
 * 用户侧看到的秒杀活动。
 *
 * <p><b>库存用 DB 的 {@code availableStock}，不读 Redis。</b>列表是低频查询，
 * 为它引入一次 Redis 往返换不来什么；而 Redis 与 DB 的偏差本来就在秒级之内，
 * 界面上「还剩 N 件」差一件不是问题。真正要求精确的是抢购那一刻，
 * 那一刀由 Lua 在 Redis 里切——**展示可以近似，扣减必须精确**。
 *
 * <p>参数里没有「我是否已经抢过」。那个判断依赖 Redis 的 per-user 标记，
 * 放在列表里意味着每条活动一次 Redis 查询；而且前端拿到它也只能决定按钮样式，
 * 真正的判定在 Lua 里。抢过的人点一下会得到 15002，这是一条能被讲清楚的提示。
 *
 * @param id          活动 ID
 * @param name        活动名称
 * @param goodsId     商品 ID
 * @param goodsName   商品名（快照）
 * @param goodsCover  封面图（快照）
 * @param goodsType   1优惠券 2实物 3虚拟物品
 * @param pointsCost  秒杀价（积分）
 * @param stock       剩余库存（DB 的 available_stock）
 * @param totalStock  总库存，用于展示「已抢 N / 共 M」
 * @param perUserLimit 每人限购
 * @param startTime   开始时间
 * @param endTime     结束时间
 * @param status      0未开始 1进行中 2已结束 3已下线
 * @param statusName  状态中文名
 * @param grabbable   当前是否可抢（时间窗内 + 有库存 + 未下线）。**不是权限判断**，
 *                    只是让按钮的禁用状态与后端的 Lua 判定大致一致；
 *                    最终能否抢到以 {@code POST /grab} 的返回为准
 */
public record SeckillActivityVO(Long id,
                                String name,
                                Long goodsId,
                                String goodsName,
                                String goodsCover,
                                Integer goodsType,
                                Integer pointsCost,
                                Integer stock,
                                Integer totalStock,
                                Integer perUserLimit,
                                LocalDateTime startTime,
                                LocalDateTime endTime,
                                Integer status,
                                String statusName,
                                boolean grabbable) {
}
