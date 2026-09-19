package com.school.forum.common.result;

import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p>错误码分段规范（与《03-功能设计》0.2 节一致），新增错误码必须落在对应区间内，
 * 避免不同模块的错误码互相冲突：
 *
 * <pre>
 *   0          成功
 *   10000-10999 通用错误
 *   11000-11999 用户域
 *   12000-12999 论坛域
 *   13000-13999 互动域
 *   14000-14999 通知域
 *   15000-15999 营销域（优惠券秒杀）
 *   16000-16999 积分与商城域
 *   50000+      系统错误
 * </pre>
 */
@Getter
public enum ErrorCode {

    // ==================== 成功 ====================
    SUCCESS(0, "success"),

    // ==================== 通用 10000-10999 ====================
    PARAM_INVALID(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    FORBIDDEN(10003, "无权限执行该操作"),
    NOT_FOUND(10004, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(10005, "请求方法不支持"),
    TOO_MANY_REQUESTS(10006, "请求过于频繁，请稍后再试"),
    IDEMPOTENT_CONFLICT(10007, "重复提交，请勿重复操作"),

    // ==================== 用户域 11000-11999 ====================
    USERNAME_EXISTS(11001, "用户名已存在"),
    PASSWORD_INCORRECT(11002, "用户名或密码错误"),
    ACCOUNT_LOCKED(11003, "账号已被临时锁定，请稍后再试"),
    ACCOUNT_BANNED(11004, "账号已被封禁"),
    ACCOUNT_DISABLED(11005, "账号已注销"),
    USER_NOT_FOUND(11006, "用户不存在"),
    STUDENT_NO_EXISTS(11007, "该学号已被绑定"),
    OLD_PASSWORD_INCORRECT(11008, "原密码不正确"),
    CANNOT_FOLLOW_SELF(11009, "不能关注自己"),
    ALREADY_FOLLOWED(11010, "已经关注过了"),

    // ==================== 论坛域 12000-12999 ====================
    POST_NOT_FOUND(12001, "帖子不存在或已删除"),
    BOARD_CLOSED(12002, "该板块当前禁止发帖"),
    SENSITIVE_WORD_HIT(12003, "内容包含违禁词"),
    BOARD_NOT_FOUND(12004, "板块不存在"),
    POST_AUDITING(12005, "帖子正在审核中"),
    POST_NO_PERMISSION(12006, "无权操作该帖子"),
    TITLE_TOO_LONG(12007, "标题长度超出限制"),
    CONTENT_TOO_LONG(12008, "正文长度超出限制"),
    TOO_MANY_IMAGES(12009, "图片数量超出限制"),
    TOO_MANY_LINKS(12010, "正文外链过多，已转人工审核"),
    TAG_NOT_FOUND(12011, "标签不存在"),
    COMMENT_CLOSED(12012, "该帖子已关闭评论"),

    // ==================== 互动域 13000-13999 ====================
    DUPLICATE_LIKE(13001, "已经点过赞了"),
    NOT_LIKED(13002, "尚未点赞，无法取消"),
    COMMENT_NOT_FOUND(13003, "评论不存在或已删除"),
    COMMENT_NO_PERMISSION(13004, "无权删除该评论"),
    ALREADY_COLLECTED(13005, "已经收藏过了"),
    NOT_COLLECTED(13006, "尚未收藏"),
    LIKE_TARGET_INVALID(13007, "点赞目标不存在"),

    // ==================== 通知域 14000-14999 ====================
    NOTIFICATION_NOT_FOUND(14001, "通知不存在"),

    // ==================== 营销域 15000-15999 ====================
    SECKILL_SOLD_OUT(15001, "已抢光，下次早点来"),
    SECKILL_ALREADY_JOINED(15002, "您已参与过本场活动"),
    SECKILL_NOT_STARTED(15003, "活动尚未开始"),
    SECKILL_ENDED(15004, "活动已结束"),
    SECKILL_NOT_WARMED_UP(15005, "活动太火爆，请稍后再试"),
    SECKILL_LIMIT_EXCEEDED(15006, "已达限购数量"),
    ACTIVITY_NOT_FOUND(15007, "活动不存在"),
    ACTIVITY_OFFLINE(15008, "活动已下线"),
    COUPON_NOT_FOUND(15009, "优惠券不存在"),
    COUPON_EXPIRED(15010, "优惠券已过期"),
    COUPON_USED(15011, "优惠券已使用"),
    ORDER_NOT_FOUND(15012, "订单不存在"),
    ORDER_STATUS_ILLEGAL(15013, "订单状态异常，无法执行该操作"),
    ORDER_EXPIRED(15014, "订单已超时关闭"),
    STOCK_DEDUCT_FAILED(15015, "库存扣减失败"),

    // ==================== 积分与商城域 16000-16999 ====================
    ALREADY_SIGNED_IN(16001, "今日已签到"),
    POINTS_NOT_ENOUGH(16002, "积分不足"),
    GOODS_NOT_FOUND(16003, "商品不存在"),
    GOODS_OFFLINE(16004, "商品已下架"),
    GOODS_STOCK_NOT_ENOUGH(16005, "商品库存不足"),
    MALL_ORDER_NOT_FOUND(16006, "兑换订单不存在"),
    MALL_ORDER_STATUS_ILLEGAL(16007, "订单状态异常，无法执行该操作"),

    // ==================== 系统 50000+ ====================
    SYSTEM_ERROR(50000, "服务器内部错误"),
    SERVICE_DEGRADED(50001, "服务繁忙，请稍后再试"),
    DB_ERROR(50002, "数据库繁忙"),
    CACHE_ERROR(50003, "缓存服务异常"),
    MQ_ERROR(50004, "消息服务异常"),
    REMOTE_CALL_ERROR(50005, "远程调用失败");

    /** 错误码 */
    private final int code;

    /** 面向用户的提示信息，可直接展示 */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
