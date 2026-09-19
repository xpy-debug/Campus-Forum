package com.school.forum.points.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商品新增 / 编辑的表单。
 *
 * <p>用普通类而不是 record：这是一个会被 Spring MVC 从 JSON 反序列化并参与
 * Jakarta Validation 的入参，需要无参构造与 setter。record 虽然 Jackson 支持，
 * 但校验注解要写在构造参数上、且无法表达「编辑时字段可空」的语义。
 *
 * <p><b>「编辑时只覆盖非 null 字段」是本表单的约定</b>（见 {@code updateGoods}）：
 * 于是「把描述改成空字符串」与「不修改描述」是两种不同的意图，
 * 分别用 {@code ""} 与 {@code null} 表达。这条约定必须在文档里写清楚——
 * 否则调用方会以为传 null 是把字段清空。
 */
@Data
public class MallGoodsForm {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 64, message = "商品名称不能超过 64 字")
    private String name;

    @Size(max = 512, message = "封面图地址过长")
    private String coverImage;

    @Size(max = 1024, message = "商品描述不能超过 1024 字")
    private String description;

    /** 1优惠券 2实物 3虚拟物品 */
    @NotNull(message = "商品类型不能为空")
    @Min(value = 1, message = "商品类型不合法")
    @Max(value = 3, message = "商品类型不合法")
    private Integer type;

    /**
     * 兑换所需积分。
     *
     * <p>下限设为 1 而不是 0：0 分商品会让 {@code WHERE balance >= 0} 恒成立，
     * 扣积分这一步形同虚设，等于把商城变成一个不受约束的领取入口。
     * 若确实要送东西，应该用管理员调整积分（{@code biz_type=5}），
     * 而不是把商品定价为 0。
     */
    @NotNull(message = "兑换积分不能为空")
    @Min(value = 1, message = "兑换积分必须大于 0")
    @Max(value = 1_000_000, message = "兑换积分超出上限")
    private Integer pointsPrice;

    /** 初始库存。仅新建时生效，后续调整需通过库存变更接口，避免与已发生的兑换冲突 */
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    /** 0草稿 1上架 2下架。不传则新建时默认草稿 */
    @Min(value = 0, message = "状态不合法")
    @Max(value = 2, message = "状态不合法")
    private Integer status;

    private Integer sortOrder;
}
