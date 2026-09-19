package com.school.forum.points.service.impl;

import com.school.forum.points.api.PointsSpendApi;
import com.school.forum.points.service.PointsAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link PointsSpendApi} 的实现。
 *
 * <p><b>本类是一层薄适配，不含任何业务规则。</b>它把 api 契约转成对
 * {@code PointsAccountService} 的调用，自己不做余额判断、不写流水、不开事务——
 * 那三件事都属于积分域内部，一旦在这一层重新实现一遍，
 * 「积分变动的唯一出口」这条不变式就出现了第二个出口。
 *
 * <p>这也解释了为什么它没有 {@code @Transactional}：
 * 事务边界属于**调用方**（秒杀支付需要「改订单状态 + 扣积分 + 改库存」原子），
 * 在适配层再开一个事务只会制造嵌套，而嵌套事务在回滚语义上是个陷阱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsSpendApiImpl implements PointsSpendApi {

    private final PointsAccountService accountService;

    @Override
    public void spendForSeckill(Long userId, int points, String orderNo, String goodsName) {
        accountService.spendForSeckill(userId, points, orderNo, goodsName);
    }

    @Override
    public void refundSeckill(Long userId, int points, String orderNo, String goodsName) {
        accountService.refundSeckill(userId, points, orderNo, goodsName);
    }
}
