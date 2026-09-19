package com.school.forum.user.convert;

import com.school.forum.user.api.UserBrief;
import com.school.forum.user.entity.User;
import com.school.forum.user.vo.UserInfoVO;
import org.springframework.beans.BeanUtils;

/**
 * 用户实体到出参的转换。
 *
 * <p>用静态方法而不是 MapStruct：本项目只有几个转换点，且转换规则不是纯粹的
 * 字段拷贝（例如 {@code UserBrief} 要剔除隐私字段、补默认头像）。为这点逻辑
 * 引入编译期代码生成，收益小于「打开文件就能读完」的直观。
 */
public final class UserConverter {

    private UserConverter() {
    }

    /**
     * 转成对外展示的简介。
     *
     * <p>刻意不带 {@code email}/{@code studentNo}：这个方法的结果会出现在
     * 帖子列表、评论区等公开位置，任何一处疏漏都会造成隐私泄露。
     * 「只暴露四个字段」比「记得排除敏感字段」可靠得多。
     */
    public static UserBrief toBrief(User user) {
        if (user == null) {
            return null;
        }
        return new UserBrief(user.getId(), user.getNickname(), user.getAvatar(), user.getLevel());
    }

    /**
     * 转成本人资料。
     *
     * <p>{@code UserInfoVO} 的字段名与 {@code User} 一致，故直接拷贝。
     * {@code password} 因为 VO 里没有同名字段而天然不会被拷过来。
     */
    public static UserInfoVO toUserInfoVO(User user) {
        UserInfoVO vo = new UserInfoVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
