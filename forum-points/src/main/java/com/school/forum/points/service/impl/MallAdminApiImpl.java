package com.school.forum.points.service.impl;

import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.points.api.MallAdminApi;
import com.school.forum.points.api.MallGoodsAdminVO;
import com.school.forum.points.api.MallGoodsForm;
import com.school.forum.points.api.MallOrderAdminVO;
import com.school.forum.points.entity.MallGoods;
import com.school.forum.points.entity.MallOrder;
import com.school.forum.points.service.MallGoodsService;
import com.school.forum.points.service.MallOrderService;
import com.school.forum.user.api.UserApi;
import com.school.forum.user.api.UserBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 商城管理端能力的实现。
 *
 * <p><b>本类只做两件事：把服务层的实体转成 api 层的 VO，以及把用户昵称补上。</b>
 * 业务规则（什么状态能改、库存怎么扣、订单状态机）全在 Service 里，
 * 这里没有任何 {@code if} 判断——一旦这里开始出现规则，
 * 就会出现「管理端一套规则、用户端另一套」的分裂。
 *
 * <p>补昵称走 {@code UserApi} 而不是 JOIN {@code t_user}：
 * 订单列表一页 20 条，用批量接口一次取回，避免 N+1。这也是本模块
 * 依赖 forum-user 的唯一原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MallAdminApiImpl implements MallAdminApi {

    private final MallGoodsService goodsService;
    private final MallOrderService orderService;
    private final UserApi userApi;

    // ==================== 商品 ====================

    @Override
    public PageResult<MallGoodsAdminVO> listGoods(Integer status, int page, int size) {
        return goodsService.page(status, page, size).map(MallGoodsAdminVO::of);
    }

    @Override
    public MallGoodsAdminVO getGoods(Long goodsId) {
        return MallGoodsAdminVO.of(goodsService.get(goodsId));
    }

    @Override
    public Long createGoods(MallGoodsForm form) {
        return goodsService.create(form);
    }

    @Override
    public void updateGoods(Long goodsId, MallGoodsForm form) {
        goodsService.update(goodsId, form);
    }

    @Override
    public void changeGoodsStatus(Long goodsId, int status) {
        if (status != MallGoods.STATUS_DRAFT
                && status != MallGoods.STATUS_ON_SALE
                && status != MallGoods.STATUS_OFF_SALE) {
            // 校验放在这里而不是等数据库的 TINYINT 截断：状态值写错时，
            // 数据库会安静地存下一个无意义的值，而商城列表按 status = 1 过滤，
            // 那个商品就永远消失了
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        goodsService.changeStatus(goodsId, status);
    }

    // ==================== 订单 ====================

    @Override
    public PageResult<MallOrderAdminVO> listOrders(Integer status, int page, int size) {
        PageResult<MallOrder> orders = orderService.page(status, page, size);

        // 一次批量取回本页所有下单人的昵称。逐条 getBrief 就是 20 次查询，
        // 而这正是 UserApi.batchGetBrief 存在的理由
        List<Long> userIds = orders.getList().stream()
                .map(MallOrder::getUserId)
                .distinct()
                .toList();
        Map<Long, UserBrief> users = userApi.batchGetBrief(userIds);

        return orders.map(order -> MallOrderAdminVO.of(order, nicknameOf(users, order.getUserId())));
    }

    @Override
    public void finishOrder(Long orderId, String remark) {
        orderService.finish(orderId, remark);
    }

    @Override
    public void cancelOrder(Long orderId, String remark) {
        orderService.cancelByAdmin(orderId, remark);
    }

    /**
     * 取昵称。
     *
     * <p>用户不存在（注销）时返回 {@code null} 而不是抛异常：一张历史订单
     * 不该因为下单人注销了就打不开。管理端看到「用户已注销」比看到 500 有用。
     */
    private String nicknameOf(Map<Long, UserBrief> users, Long userId) {
        UserBrief brief = users.get(userId);
        return brief == null ? null : brief.nickname();
    }
}
