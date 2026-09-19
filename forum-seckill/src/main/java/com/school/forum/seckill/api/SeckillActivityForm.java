package com.school.forum.seckill.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀活动的新增 / 编辑表单。
 *
 * <p>用普通类而不是 record：它是 Spring MVC 从 JSON 反序列化并参与校验的入参，
 * 需要无参构造与 setter（与 {@code MallGoodsForm} 同一个理由）。
 *
 * <p><b>没有 {@code goodsName} / {@code goodsCover} 这类字段。</b>
 * 商品资料由服务端根据 {@code goodsId} 从积分商城取回后<b>快照</b>写入，
 * 不允许前端直接提交——否则一次伪造的请求就能让活动页显示任意商品名，
 * 而订单里记录的快照同样是伪造的，那等于凭空造出一个不存在的商品。
 */
@Data
public class SeckillActivityForm {

    /** 关联的商城商品 ID。服务端会校验它确实存在 */
    @NotNull(message = "必须指定商品")
    private Long goodsId;

    @NotBlank(message = "活动名称不能为空")
    @Size(max = 64, message = "活动名称不能超过 64 字")
    private String name;

    /**
     * 秒杀价（积分）。
     *
     * <p>下限为 1 而不是 0：0 分活动会让 {@code WHERE balance >= 0} 恒成立，
     * 扣积分这一步形同虚设。要送东西应当走管理员调整积分，
     * 而不是把活动定价为 0（与 {@code MallGoodsForm.pointsPrice} 同一条取舍）。
     */
    @NotNull(message = "秒杀价不能为空")
    @Min(value = 1, message = "秒杀价必须大于 0")
    @Max(value = 1_000_000, message = "秒杀价超出上限")
    private Integer pointsCost;

    /** 活动总库存。仅新建时生效——活动开始后再改总量会让库存恒等式失去意义 */
    @Min(value = 1, message = "库存至少为 1")
    @Max(value = 1_000_000, message = "库存超出上限")
    private Integer totalStock;

    /** 每人限购。当前应配为 1；调大需同步调整订单表唯一索引，见《04》4.6 节 */
    @Min(value = 1, message = "限购至少为 1")
    @Max(value = 10, message = "限购上限为 10")
    private Integer perUserLimit;

    /**
     * 开始时间。
     *
     * <p><b>必须显式声明格式。</b>Spring 的 {@code spring.jackson.date-format} 只作用于
     * {@code java.util.Date}，对 {@code LocalDateTime} 不生效——后者默认走 ISO-8601，
     * 于是它只认 {@code 2026-09-17T22:00:00}，而前端 {@code el-date-picker} 的
     * {@code value-format} 提交的是 {@code 2026-09-17 22:00:00}（本项目的日期约定格式）。
     * 不加这行注解，管理端一提交建活动就返回 10001「请求格式不正确」，
     * 而错误信息里完全看不出是时间的格式问题。
     */
    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 结束时间，格式约定同 {@link #startTime} */
    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 支付超时秒数。默认 900（15 分钟）。太短会让用户来不及支付，太长会让库存被长期占用 */
    @Min(value = 30, message = "支付超时不得少于 30 秒")
    @Max(value = 86_400, message = "支付超时不得超过 24 小时")
    private Integer payTimeoutSec;

    /** 开始前多少分钟预热 */
    @Min(value = 0, message = "预热提前量不能为负")
    @Max(value = 1440, message = "预热提前量不得超过 24 小时")
    private Integer warmupMinutes;

    /** 0未开始 1进行中 2已结束 3已下线。不传则新建时默认未开始 */
    @Min(value = 0, message = "状态不合法")
    @Max(value = 3, message = "状态不合法")
    private Integer status;
}
