package com.school.forum.points.api;

import com.school.forum.common.result.PageResult;

import java.util.List;

/**
 * 商城管理端对 {@code forum-admin} 暴露的契约。
 *
 * <p><b>为什么管理能力要单独一个接口，而不是把方法加到用户侧的 Service 上：</b>
 * 这样「哪些能力是管理端的」在类型层面就有答案——{@code forum-admin} 只依赖本接口，
 * 而本接口里的每个方法都对应一个必然要管理员权限的操作。
 * 如果混在一起，判断某个方法该不该加权限校验就要逐行去读实现。
 *
 * <p><b>本接口不负责权限校验。</b>权限由 {@code @RequireRole} 在拦截器层完成
 * （见《02-架构设计》ADR-009）。在这里再写一遍 {@code if (!isAdmin())} 属于重复劳动，
 * 且会让人误以为「不调这个接口就安全」——真正的边界是接口暴露面，不是方法内部的判断。
 *
 * <p><b>判断标准</b>（与 {@code UserApi} 一致）：把 forum-points 的实现代码整体删掉、
 * 只留 api 包，项目仍应编译通过。因此这里只出现本包内的 DTO，
 * 绝不引用 {@code entity}、{@code mapper} 或 {@code service}。
 */
public interface MallAdminApi {

    /**
     * 商品列表（管理端）。与用户侧的区别是**不过滤状态**——
     * 草稿与已下架的商品也必须能被管理员看到，否则改完就找不着了。
     *
     * @param status 状态筛选，null 表示全部
     * @param page   页码，从 1 开始
     */
    PageResult<MallGoodsAdminVO> listGoods(Integer status, int page, int size);

    /** 商品详情（管理端），含草稿与下架商品。不存在时返回 null */
    MallGoodsAdminVO getGoods(Long goodsId);

    /** 新建商品。返回新商品 ID */
    Long createGoods(MallGoodsForm form);

    /** 编辑商品。只覆盖 form 中非 null 的字段 */
    void updateGoods(Long goodsId, MallGoodsForm form);

    /**
     * 上架 / 下架 / 转为草稿。
     *
     * <p>刻意不提供「删除商品」：物理删除会让历史订单变成孤儿，
     * 而订单是给用户看的凭证。下架即可达到「不再出现在商城」的目的。
     */
    void changeGoodsStatus(Long goodsId, int status);

    // ==================== 兑换订单 ====================

    /**
     * 兑换订单列表（管理端），可见全部用户的订单。
     *
     * @param status 状态筛选，null 表示全部
     */
    PageResult<MallOrderAdminVO> listOrders(Integer status, int page, int size);

    /**
     * 标记订单已发放。
     *
     * <p>订单必须处于「待发放」，否则返回 16007。这条判断由
     * {@code WHERE status = 0} 的条件更新保证，本方法不做「先查再判」。
     */
    void finishOrder(Long orderId, String remark);

    /**
     * 管理员取消订单并退回积分与库存。
     *
     * <p>存在的理由是「商品发不出来了」这类只有管理员能处理的情况：
     * 库存对不上、实物损坏、活动临时取消。用户侧的取消能力覆盖不到这些，
     * 而让运营去改数据库是不可接受的。
     *
     * <p>与用户取消走同一段退回逻辑（同一个事务、同一条状态条件），
     * 因此不存在「管理端能重复退款」这种只有一侧才有的问题。
     *
     * @param remark 取消原因，会记进订单备注供后续追溯
     */
    void cancelOrder(Long orderId, String remark);
}
