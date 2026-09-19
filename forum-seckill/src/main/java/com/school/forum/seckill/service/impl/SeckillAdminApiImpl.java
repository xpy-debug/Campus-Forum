package com.school.forum.seckill.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.points.api.MallAdminApi;
import com.school.forum.points.api.MallGoodsAdminVO;
import com.school.forum.seckill.api.SeckillActivityAdminVO;
import com.school.forum.seckill.api.SeckillActivityForm;
import com.school.forum.seckill.api.SeckillAdminApi;
import com.school.forum.seckill.api.SeckillOrderAdminVO;
import com.school.forum.seckill.entity.SeckillActivity;
import com.school.forum.seckill.entity.SeckillOrder;
import com.school.forum.seckill.mapper.SeckillActivityMapper;
import com.school.forum.seckill.mapper.SeckillOrderMapper;
import com.school.forum.seckill.service.SeckillService;
import com.school.forum.user.api.UserApi;
import com.school.forum.user.api.UserBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 秒杀管理端能力的实现。
 *
 * <p><b>它承担的唯一职责是「跨模块取数」与「实体 ↔ api VO 的转换」。</b>
 * 库存在哪一层、什么时候回补、订单能不能取消，这些规则全在 {@link SeckillService} 里。
 * 这一层一旦开始出现 {@code if}，就会出现「管理端能这么改、用户端不行」的规则分裂。
 *
 * <p>两处跨模块取数都走 api 包：商品资料经 {@code MallAdminApi}（校验商品真实存在），
 * 用户昵称经 {@code UserApi.batchGetBrief}（一次取回一页，避免 N+1）。
 * 秒杀域不查 {@code t_mall_goods}，也不查 {@code t_user}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillAdminApiImpl implements SeckillAdminApi {

    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillService seckillService;
    private final MallAdminApi mallAdminApi;
    private final UserApi userApi;

    // ==================== 活动 ====================

    @Override
    public PageResult<SeckillActivityAdminVO> listActivities(Integer status, int page, int size) {
        Page<SeckillActivity> result = activityMapper.selectPage(
                Page.of(page, size),
                Wrappers.<SeckillActivity>lambdaQuery()
                        .eq(status != null, SeckillActivity::getStatus, status)
                        .orderByDesc(SeckillActivity::getStartTime));
        return PageResult.ofPage(result.getRecords(), result.getTotal(), size)
                .map(SeckillActivityAdminVO::of);
    }

    @Override
    public SeckillActivityAdminVO getActivity(Long activityId) {
        return SeckillActivityAdminVO.of(activityMapper.selectById(activityId));
    }

    @Override
    public Long createActivity(SeckillActivityForm form) {
        requireValidWindow(form);

        SeckillActivity activity = new SeckillActivity();
        applyGoods(activity, form.getGoodsId());
        activity.setName(form.getName());
        activity.setPointsCost(form.getPointsCost());

        int total = form.getTotalStock() == null ? 0 : form.getTotalStock();
        activity.setTotalStock(total);
        // 三个数字在创建时必须一次写对：恒等式 total = available + locked + sold
        activity.setAvailableStock(total);
        activity.setLockedStock(0);
        activity.setSoldCount(0);
        activity.setPerUserLimit(form.getPerUserLimit() == null ? 1 : form.getPerUserLimit());
        activity.setStartTime(form.getStartTime());
        activity.setEndTime(form.getEndTime());
        activity.setPayTimeoutSec(form.getPayTimeoutSec() == null ? 900 : form.getPayTimeoutSec());
        activity.setWarmupMinutes(form.getWarmupMinutes() == null ? 10 : form.getWarmupMinutes());
        // 缺省是「未开始」而不是「进行中」：直接上线的活动若有配置错误，
        // 用户会立刻抢到一堆错的东西；草稿状态给了运营一次复查的机会
        activity.setStatus(form.getStatus() == null ? SeckillActivity.STATUS_NOT_STARTED : form.getStatus());
        activity.setVersion(0);

        activityMapper.insert(activity);
        return activity.getId();
    }

    @Override
    public void updateActivity(Long activityId, SeckillActivityForm form) {
        SeckillActivity activity = requireActivity(activityId);
        if (form.getStartTime() != null && form.getEndTime() != null) {
            requireValidWindow(form);
        } else if (form.getStartTime() != null && !form.getStartTime().isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        } else if (form.getEndTime() != null && !activity.getStartTime().isBefore(form.getEndTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }

        if (form.getName() != null) {
            activity.setName(form.getName());
        }
        if (form.getPointsCost() != null) {
            activity.setPointsCost(form.getPointsCost());
        }
        // 换商品就重新快照。只改 goodsId 而不刷快照，活动页会显示上一个商品的名字
        if (form.getGoodsId() != null && !Objects.equals(form.getGoodsId(), activity.getGoodsId())) {
            applyGoods(activity, form.getGoodsId());
        }
        if (form.getPerUserLimit() != null) {
            activity.setPerUserLimit(form.getPerUserLimit());
        }
        if (form.getStartTime() != null) {
            activity.setStartTime(form.getStartTime());
        }
        if (form.getEndTime() != null) {
            activity.setEndTime(form.getEndTime());
        }
        if (form.getPayTimeoutSec() != null) {
            activity.setPayTimeoutSec(form.getPayTimeoutSec());
        }
        if (form.getWarmupMinutes() != null) {
            activity.setWarmupMinutes(form.getWarmupMinutes());
        }
        if (form.getStatus() != null) {
            activity.setStatus(form.getStatus());
        }

        // 刻意不写回 totalStock / availableStock / lockedStock / soldCount：
        // 它们是活动进行中的实时库存，让编辑接口能改库存会让「总数 = 可售 + 锁定 + 已售」
        // 这条恒等式失去基准。MyBatis-Plus 的 updateById 只更新非 null 字段，
        // 而上面这些字段是在 selectById 之后原样带过来的，所以必须显式清空
        activity.setTotalStock(null);
        activity.setAvailableStock(null);
        activity.setLockedStock(null);
        activity.setSoldCount(null);

        activityMapper.updateById(activity);
    }

    @Override
    public void changeActivityStatus(Long activityId, int status) {
        if (status != SeckillActivity.STATUS_NOT_STARTED
                && status != SeckillActivity.STATUS_RUNNING
                && status != SeckillActivity.STATUS_ENDED
                && status != SeckillActivity.STATUS_OFFLINE) {
            // 校验放在这里而不是等 TINYINT 静默截断：写歪的状态值会让活动
            // 从用户侧彻底消失（列表按 status <> 3 过滤），而数据库里看不出异常
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        SeckillActivity activity = requireActivity(activityId);
        SeckillActivity update = new SeckillActivity();
        update.setId(activity.getId());
        update.setStatus(status);
        activityMapper.updateById(update);
    }

    @Override
    public boolean warmup(Long activityId) {
        return seckillService.warmupOne(activityId);
    }

    @Override
    public ReconcileVO reconcile(Long activityId) {
        SeckillService.ReconcileOutcome outcome = seckillService.reconcile(activityId, true);
        return new ReconcileVO(outcome.activityId(), outcome.redisStock(), outcome.dbAvailable(), outcome.fixed());
    }

    // ==================== 订单 ====================

    @Override
    public PageResult<SeckillOrderAdminVO> listOrders(Integer status, int page, int size) {
        Page<SeckillOrder> result = orderMapper.selectPage(
                Page.of(page, size),
                Wrappers.<SeckillOrder>lambdaQuery()
                        .eq(status != null, SeckillOrder::getStatus, status)
                        .orderByDesc(SeckillOrder::getId));

        List<Long> userIds = result.getRecords().stream()
                .map(SeckillOrder::getUserId)
                .distinct()
                .toList();
        Map<Long, UserBrief> users = userApi.batchGetBrief(userIds);

        return PageResult.ofPage(result.getRecords(), result.getTotal(), size)
                .map(order -> SeckillOrderAdminVO.of(order, nicknameOf(users, order.getUserId())));
    }

    @Override
    public void finishOrder(String orderNo) {
        seckillService.finish(orderNo);
    }

    @Override
    public void cancelOrder(String orderNo, String remark) {
        seckillService.cancelByAdmin(orderNo, remark);
    }

    // ==================== 内部 ====================

    /**
     * 取回商品资料并快照进活动。
     *
     * <p>校验商品存在是<b>必须的</b>：否则管理员可以创建一个指向不存在商品的活动，
     * 用户抢到之后订单里记录的是一串没有意义的 ID，而发货环节根本无从下手。
     */
    private void applyGoods(SeckillActivity activity, Long goodsId) {
        MallGoodsAdminVO goods = mallAdminApi.getGoods(goodsId);
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        activity.setGoodsId(goods.id());
        activity.setGoodsName(goods.name());
        activity.setGoodsCover(goods.coverImage());
        activity.setGoodsType(goods.type());
    }

    private void requireValidWindow(SeckillActivityForm form) {
        if (form.getStartTime() == null || form.getEndTime() == null
                || !form.getStartTime().isBefore(form.getEndTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private SeckillActivity requireActivity(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    /** 用户注销后昵称取不到，返回 null 而不是抛异常——历史订单不该因此打不开 */
    private String nicknameOf(Map<Long, UserBrief> users, Long userId) {
        UserBrief brief = users.get(userId);
        return brief == null ? null : brief.nickname();
    }
}
