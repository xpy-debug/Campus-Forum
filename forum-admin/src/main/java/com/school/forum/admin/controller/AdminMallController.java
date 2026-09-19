package com.school.forum.admin.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.auth.LoginUser;
import com.school.forum.infrastructure.web.auth.RequireRole;
import com.school.forum.points.api.MallAdminApi;
import com.school.forum.points.api.MallGoodsAdminVO;
import com.school.forum.points.api.MallGoodsForm;
import com.school.forum.points.api.MallOrderAdminVO;
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
 * 积分商城管理接口。上下文路径为 {@code /api}，故实际路径是 {@code /api/admin/mall/*}。
 *
 * <p><b>整个类要求管理员角色。</b>注解锁在类上而不是逐个方法上：
 * 这样「新加一个管理接口」默认就是受保护的，忘记加注解的后果从
 * 「接口裸奔」变成「接口访问不了」——后者的错误会在自测时立刻暴露，
 * 而前者可能几个月都没人发现。
 *
 * <p>本类只调用 {@code forum-points} 的 {@code api} 包，不引用它的
 * service / mapper / entity，也不做任何业务判断——它只是把 HTTP 请求
 * 翻译成 API 调用，规则都在积分域内部。这样后台改不动积分域的规则，
 * 也就不会出现「网页上能这么改、接口里改不了」的分裂。
 */
@RestController
@RequestMapping("/admin/mall")
@RequireRole(LoginUser.ROLE_ADMIN)
@RequiredArgsConstructor
public class AdminMallController {

    private final MallAdminApi mallAdminApi;

    // ==================== 商品 ====================

    /**
     * 商品列表。
     *
     * <p>与用户侧的关键差别：**不过滤状态**。草稿和已下架的商品管理员必须能看到，
     * 否则改完一个商品就再也找不到它了。
     *
     * @param status 状态筛选，不传表示全部
     */
    @GetMapping("/goods")
    public Result<PageResult<MallGoodsAdminVO>> listGoods(@RequestParam(required = false) Integer status,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return Result.ok(mallAdminApi.listGoods(status, page, size));
    }

    /** 商品详情，含草稿与已下架 */
    @GetMapping("/goods/{goodsId}")
    public Result<MallGoodsAdminVO> getGoods(@PathVariable Long goodsId) {
        return Result.ok(mallAdminApi.getGoods(goodsId));
    }

    /**
     * 新建商品。
     *
     * <p>新建默认是**草稿**而不是上架（见 {@code MallGoodsForm.status}），
     * 因此这个接口常见的使用方式是「先建好、配图配价、再上架」。
     *
     * @return 新商品 ID
     */
    @PostMapping("/goods")
    public Result<Long> createGoods(@RequestBody @Valid MallGoodsForm form) {
        return Result.ok(mallAdminApi.createGoods(form));
    }

    /**
     * 编辑商品。
     *
     * <p><b>用 PUT 但语义是「部分更新」</b>：表单里为 null 的字段保持不变。
     * 这不是对 REST 的误解，而是刻意的取舍——商品有库存、销量这类
     * 不该由编辑接口维护的字段，若要求前端每次提交完整对象，
     * 前端就得先把它们读出来再原样传回，反而多一次失败的机会。
     */
    @PutMapping("/goods/{goodsId}")
    public Result<Void> updateGoods(@PathVariable Long goodsId,
                                    @RequestBody @Valid MallGoodsForm form) {
        mallAdminApi.updateGoods(goodsId, form);
        return Result.ok();
    }

    /**
     * 上架 / 下架 / 转为草稿。
     *
     * <p>单独一个接口而不是复用编辑接口：状态变更需要立即生效并清缓存，
     * 与「改个描述」是两种操作意图，混在一起会让「为什么改了状态没生效」
     * 这类问题难以排查。
     *
     * @param status 0草稿 1上架 2下架
     */
    @PostMapping("/goods/{goodsId}/status")
    public Result<Void> changeGoodsStatus(@PathVariable Long goodsId,
                                          @RequestParam int status) {
        mallAdminApi.changeGoodsStatus(goodsId, status);
        return Result.ok();
    }

    // ==================== 兑换订单 ====================

    /** 兑换订单列表，可见全部用户。下单人昵称由积分域调 UserApi 补齐 */
    @GetMapping("/orders")
    public Result<PageResult<MallOrderAdminVO>> listOrders(@RequestParam(required = false) Integer status,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return Result.ok(mallAdminApi.listOrders(status, page, size));
    }

    /**
     * 标记订单已发放。只有「待发放」的订单能发放，否则返回 16007。
     *
     * @param remark 发放说明（快递单号、领取地点等），可选
     */
    @PostMapping("/orders/{orderId}/finish")
    public Result<Void> finishOrder(@PathVariable Long orderId,
                                    @RequestParam(required = false) String remark) {
        mallAdminApi.finishOrder(orderId, remark);
        return Result.ok();
    }

    /**
     * 取消订单并退回积分与库存。
     *
     * <p>用于「商品发不出来了」的场景：库存对不上、实物损坏、活动取消。
     * 退回逻辑与用户自行取消完全一致，因此不存在「管理端取消能重复退款」的漏洞。
     */
    @PostMapping("/orders/{orderId}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long orderId,
                                    @RequestParam(required = false) String remark) {
        mallAdminApi.cancelOrder(orderId, remark);
        return Result.ok();
    }
}
