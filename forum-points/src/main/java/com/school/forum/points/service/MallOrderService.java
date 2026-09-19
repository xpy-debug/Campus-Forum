package com.school.forum.points.service;

import com.school.forum.common.result.PageResult;
import com.school.forum.points.dto.MallOrderCreateRequest;
import com.school.forum.points.entity.MallOrder;
import com.school.forum.points.vo.MallOrderVO;

/**
 * 兑换订单。
 *
 * <p>下单是一条**全同步**链路：扣库存 → 扣积分 → 建订单，三步在同一个事务里。
 * 这与秒杀刻意不同（秒杀靠 Redis 预扣 + MQ 异步落库）：
 * 商城没有瞬时峰值，也就没有必须削的峰，而异步化会引入
 * 「积分扣了但订单没建出来」这类需要补偿的中间态。见《02-架构设计》ADR-008。
 *
 * <p>代价是下单要等两次数据库写往返。对一个每天几十单的校园商城来说，
 * 这点延迟换来的是「账目永远对得上」，是划算的。
 */
public interface MallOrderService {

    /**
     * 兑换。
     *
     * <p>可能抛出的业务异常：16003 商品不存在、16004 已下架、
     * 16005 库存不足、16002 积分不足。任一失败，整笔兑换回滚——
     * 不会出现「库存扣了但积分没扣」这种半成品状态。
     */
    MallOrderVO create(Long userId, MallOrderCreateRequest request);

    /** 我的兑换记录，倒序游标分页 */
    PageResult<MallOrderVO> listMine(Long userId, String cursor, int size);

    /**
     * 用户取消自己的兑换。
     *
     * <p>只能取消自己的订单：传入他人的订单 ID 返回 10003 而不是 16006。
     * 这里刻意不把「别人的订单」伪装成「不存在」——校园论坛的用户之间没有
     * 需要防备的探测动机，而明确的错误码能让接口调试少绕一圈。
     */
    MallOrderVO cancelByOwner(Long userId, Long orderId);

    // ==================== 管理侧 ====================

    /**
     * 订单列表（管理端），可见全部用户。
     *
     * @param status 状态筛选，null 表示全部
     */
    PageResult<MallOrder> page(Integer status, int page, int size);

    /** 标记已发放。订单不是待发放状态时抛 16007 */
    void finish(Long orderId, String remark);

    /**
     * 管理员取消订单（例如商品已发不出、库存对不上）。
     *
     * <p>与用户取消走同一段退回逻辑，只是权限判据不同——
     * 因此它不是「另一种取消」，不另写一套实现。
     */
    MallOrderVO cancelByAdmin(Long orderId, String remark);
}
