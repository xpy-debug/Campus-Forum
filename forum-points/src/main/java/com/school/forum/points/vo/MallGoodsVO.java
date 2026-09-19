package com.school.forum.points.vo;

/**
 * 用户侧看到的商品。
 *
 * <p><b>比管理端多的是「跟我有没有关系」这类字段</b>（{@code canExchange}、
 * {@code lackPoints}），它们取决于**调用者自己的余额**，与商品本身无关。
 * 也正因为带着调用者相关的结论，本 VO 不适合被缓存——用它做列表缓存，
 * 下一个余额不同的用户会读到上一个人的结论。用户侧列表因此不缓存，直接查库。
 *
 * @param id           商品 ID
 * @param name         名称
 * @param coverImage   封面图
 * @param description  描述
 * @param type         1优惠券 2实物 3虚拟物品。前端据此显示「券」「实物」等标签
 * @param pointsPrice  所需积分
 * @param stock        剩余库存。为 0 时显示「已兑完」
 * @param soldCount    已兑数量。它同时是「有多少人换过」的社会证明，值得展示
 * @param canExchange  当前用户能否兑换（积分够 + 有库存）。余额不足或未登录时为 false
 * @param lackPoints   还差多少积分。够兑换时为 0；库存不足时无意义，同样为 0
 */
public record MallGoodsVO(Long id,
                          String name,
                          String coverImage,
                          String description,
                          Integer type,
                          Integer pointsPrice,
                          Integer stock,
                          Integer soldCount,
                          boolean canExchange,
                          int lackPoints) {
}
