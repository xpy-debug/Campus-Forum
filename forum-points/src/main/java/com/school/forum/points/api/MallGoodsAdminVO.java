package com.school.forum.points.api;

import com.school.forum.points.entity.MallGoods;

/**
 * 管理端看到的商品视图。
 *
 * <p><b>与用户侧的 {@code MallGoodsVO} 是两个类，不是同一个类的两种用法。</b>
 * 这不是重复代码，而是两种不同的契约：
 * <ul>
 *   <li>用户侧只需要「能兑换什么」，因此带 {@code canExchange}（积分是否够、有没有库存）
 *       这类**因人而异**的字段；</li>
 *   <li>管理侧需要「这个商品配置成什么样了」，因此带状态、销量、排序权重，
 *       且要能看到草稿与已下架的商品。</li>
 * </ul>
 * 合并成一个类的话，用户侧会拿到一堆 status 之类的内部字段，
 * 而每次给后台加一个字段（比如「创建人」「最近修改人」）都会外泄到公开接口上。
 *
 * <p>它位于 {@code api} 包内，因为它是 {@link MallAdminApi} 的返回类型——
 * api 包不引用 {@code entity} 之外的类型，而这里引用 {@code MallGoods}
 * 只是为了用它的常量（状态码语义只应有一处定义）。
 */
public record MallGoodsAdminVO(Long id,
                               String name,
                               String coverImage,
                               String description,
                               Integer type,
                               Integer pointsPrice,
                               Integer stock,
                               Integer soldCount,
                               Integer status,
                               Integer sortOrder,
                               String createTime,
                               String updateTime) {

    /**
     * 由实体转换。
     *
     * <p>时间格式化成字符串而不是让 Jackson 自动序列化 {@code LocalDateTime}：
     * 后者依赖全局的 {@code date-format} 配置，一旦有人改了全局配置，
     * 这个接口的输出就跟着变。在转换处显式定死格式，接口契约就不受全局配置影响。
     */
    public static MallGoodsAdminVO of(MallGoods goods) {
        if (goods == null) {
            return null;
        }
        return new MallGoodsAdminVO(
                goods.getId(), goods.getName(), goods.getCoverImage(), goods.getDescription(),
                goods.getType(), goods.getPointsPrice(), goods.getStock(), goods.getSoldCount(),
                goods.getStatus(), goods.getSortOrder(),
                format(goods.getCreateTime()), format(goods.getUpdateTime()));
    }

    private static String format(java.time.LocalDateTime time) {
        return time == null ? null : time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
