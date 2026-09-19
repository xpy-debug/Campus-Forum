package com.school.forum.admin.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.auth.LoginUser;
import com.school.forum.infrastructure.web.auth.RequireRole;
import com.school.forum.seckill.api.SeckillActivityAdminVO;
import com.school.forum.seckill.api.SeckillActivityForm;
import com.school.forum.seckill.api.SeckillAdminApi;
import com.school.forum.seckill.api.SeckillOrderAdminVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀管理接口。实际路径 {@code /api/admin/seckill/*}。
 *
 * <p>与 {@code AdminMallController} 一样，<b>注解锁在类上而不是逐个方法上</b>：
 * 以后新增管理接口默认就是受保护的，忘记加注解的后果从「接口裸奔」
 * 变成「接口访问不了」——后者在自测时立刻暴露。
 *
 * <p>它只调用 {@code forum-seckill} 的 {@code api} 包，不做任何业务判断。
 * 尤其是<b>库存与超时的规则</b>：那种「管理员手动补库存」的入口
 * 一旦开出来，就没有什么能阻止它把库存改成比实际多，
 * 而超卖的后果由用户承担。需要补货就新建一场活动。
 */
@RestController
@RequestMapping("/admin/seckill")
@RequireRole(LoginUser.ROLE_ADMIN)
@RequiredArgsConstructor
public class AdminSeckillController {

    private final SeckillAdminApi seckillAdminApi;

    // ==================== 活动 ====================

    /**
     * 活动列表。**不过滤状态**——草稿、已下线的活动管理员必须能看到，
     * 否则改完一场活动就再也找不回来了。
     *
     * @param status 状态筛选，不传表示全部
     */
    @GetMapping("/activities")
    public Result<PageResult<SeckillActivityAdminVO>> listActivities(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(seckillAdminApi.listActivities(status, page, size));
    }

    @GetMapping("/activities/{activityId}")
    public Result<SeckillActivityAdminVO> getActivity(@PathVariable Long activityId) {
        return Result.ok(seckillAdminApi.getActivity(activityId));
    }

    /**
     * 新建活动。
     *
     * <p>只传 {@code goodsId}，商品名与封面由服务端从积分商城取回后快照写入——
     * 不接受前端提交商品资料，否则一次伪造请求就能造出「不存在的商品」的活动。
     */
    @PostMapping("/activities")
    public Result<Long> createActivity(@RequestBody @Valid SeckillActivityForm form) {
        return Result.ok(seckillAdminApi.createActivity(form));
    }

    /** 编辑活动。{@code null} 表示不修改；库存与已售数不可通过本接口调整 */
    @PutMapping("/activities/{activityId}")
    public Result<Void> updateActivity(@PathVariable Long activityId,
                                       @RequestBody @Valid SeckillActivityForm form) {
        seckillAdminApi.updateActivity(activityId, form);
        return Result.ok();
    }

    /**
     * 上下线。
     *
     * @param status 0未开始 1进行中 2已结束 3已下线
     */
    @PostMapping("/activities/{activityId}/status")
    public Result<Void> changeStatus(@PathVariable Long activityId,
                                     @RequestParam int status) {
        seckillAdminApi.changeActivityStatus(activityId, status);
        return Result.ok();
    }

    /**
     * 手动预热。
     *
     * <p>预热任务连续失败、或活动马上开始而定时扫描还没跑到时的应急入口。
     * 返回本次是否为「首次写入库存」。
     */
    @PostMapping("/activities/{activityId}/warmup")
    public Result<Boolean> warmup(@PathVariable Long activityId) {
        return Result.ok(seckillAdminApi.warmup(activityId));
    }

    /**
     * 库存对账。
     *
     * <p>与定时任务的区别：它**不跳过进行中的活动**。定时任务跳过是为了避免
     * 洪峰期间用落后的 DB 值覆盖 Redis 而超卖；人工调用意味着调用者
     * 已经确认当前没有在途请求。
     */
    @PostMapping("/activities/{activityId}/reconcile")
    public Result<SeckillAdminApi.ReconcileVO> reconcile(@PathVariable Long activityId) {
        return Result.ok(seckillAdminApi.reconcile(activityId));
    }

    // ==================== 订单 ====================

    /** 全部秒杀订单。下单人昵称由秒杀域调 UserApi 补齐 */
    @GetMapping("/orders")
    public Result<PageResult<SeckillOrderAdminVO>> listOrders(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(seckillAdminApi.listOrders(status, page, size));
    }

    /**
     * 标记已发放：已支付 → 已完成。状态不符返回 15013。
     *
     * @param remark 发放说明（领取地点、快递单号等），可选
     */
    @PostMapping("/orders/{orderNo}/finish")
    public Result<Void> finishOrder(@PathVariable String orderNo,
                                    @RequestParam(required = false) String remark) {
        seckillAdminApi.finishOrder(orderNo);
        return Result.ok();
    }

    /**
     * 管理员取消订单。
     *
     * <p>未支付的直接关闭；<b>已支付的会退回积分</b>并把库存从已售退回可售。
     * 用于「实物发不出来」「活动被叫停」这类只有管理员能处理的情况。
     */
    @PostMapping("/orders/{orderNo}/cancel")
    public Result<Void> cancelOrder(@PathVariable String orderNo,
                                    @RequestParam(required = false) String remark) {
        seckillAdminApi.cancelOrder(orderNo, remark);
        return Result.ok();
    }
}
