package com.school.forum.points.api;

/**
 * 积分扣减对<b>其他业务域</b>暴露的契约。
 *
 * <p><b>为什么需要它，而不是让 forum-seckill 直接调 {@code PointsAccountService}：</b>
 * 积分域的一切写入都必须经过「唯一的出口」（见 {@code PointsAccountService} 的类注释）——
 * 改余额与写流水必须同时发生、条件更新必须带 {@code balance >= ?}、流水必须有
 * {@code balance_after} 快照。这些规则一旦被别的模块绕过（直接注入 mapper、直接写 SQL），
 * 「积分怎么变的」就会失去唯一答案。
 *
 * <p>把能力收敛成一个只包含两个方法的接口，收益有两个：
 * <ul>
 *   <li><b>编译期边界</b>：秒杀域能做的事，在类型层面就限定为「扣分」与「退分」，
 *       它拿不到 {@code initAccount}、{@code earnSignin} 这些与它无关的能力；</li>
 *   <li><b>依赖单向</b>：SEC KILL → POINTS，而积分域不知道秒杀的存在，
 *       模块图不会成环。</li>
 * </ul>
 *
 * <p><b>与 {@link MallAdminApi} 的约定一致</b>：把 forum-points 的实现代码整体删掉、
 * 只留 api 包，项目仍应编译通过。因此本接口只出现基本类型与字符串，
 * 绝不引用 {@code entity}、{@code mapper} 或 {@code service}。
 *
 * <p><b>本接口不做权限校验、不做事务边界声明。</b>调用方负责在自己事务里调用，
 * 失败时由调用方回滚——秒杀支付就是这样：扣分失败即整笔支付回滚，订单留在待支付。
 */
public interface PointsSpendApi {

    /**
     * 扣减积分（秒杀支付）。
     *
     * @param userId    用户 ID
     * @param points    扣减的积分数，必须为正
     * @param orderNo   秒杀订单号，作为流水的业务标识（{@code biz_id}），
     *                  由 {@code uk_user_biz} 保证同一笔订单不会扣两次
     * @param goodsName 商品名，仅用于拼流水备注，让积分明细页能读出「花在哪了」
     * @throws com.school.forum.common.exception.BizException 余额不足（16002）
     */
    void spendForSeckill(Long userId, int points, String orderNo, String goodsName);

    /**
     * 退回积分（取消已支付的秒杀订单）。
     *
     * <p>退回记为一笔正数流水并计入 {@code total_earned}，
     * 从而保住「{@code balance = total_earned - total_spent}」与
     * 「{@code balance = SUM(change_amount)}」两条不变式。
     *
     * @param points 退回的积分数。小于等于 0 时不做任何事
     */
    void refundSeckill(Long userId, int points, String orderNo, String goodsName);
}
