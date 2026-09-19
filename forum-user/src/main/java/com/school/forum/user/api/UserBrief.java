package com.school.forum.user.api;

import java.io.Serializable;

/**
 * 用户的对外简介。其他模块只能通过本类型了解「用户是谁」。
 *
 * <p><b>为什么单独定义而不是直接返回 {@code User} 实体：</b>
 * 实体里含密码、邮箱、学号、手机号。跨模块直接传实体，等于让每个调用方
 * 都能看到这些字段，只要有人顺手 {@code return user} 就泄露出去了。
 * 这个 DTO 只带展示必需的四项，把「暴露面」写死在类型里而不是靠自觉。
 *
 * <p>用 {@code record} 而非 {@code @Data} 类：它是跨模块的只读契约，
 * 没有 setter 就没有「调用方悄悄改掉传参」的可能。
 *
 * @param id       用户 ID
 * @param nickname 昵称
 * @param avatar   头像 URL
 * @param level    等级，帖子详情页会跟着作者昵称一起展示
 */
public record UserBrief(Long id, String nickname, String avatar, Integer level) implements Serializable {

    /** 用户不存在时的占位值。帖子作者注销后，历史帖子仍要能渲染出来 */
    public static UserBrief unknown(Long userId) {
        return new UserBrief(userId, "已注销用户", "", 1);
    }
}
