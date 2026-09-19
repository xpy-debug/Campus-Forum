package com.school.forum.user.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户资料出参。
 *
 * <p><b>字段名与 {@code User} 实体逐字对应</b>，转换时直接靠
 * {@code BeanUtils.copyProperties} 拷贝，不必逐个赋值。代价是两边改名不会报错，
 * 所以新增字段时务必两处同时添加。
 *
 * <p>这里是「本人视角」的资料，含邮箱、学号等隐私字段，只能返回给用户自己；
 * 展示给他人的信息请用 {@code UserBrief}。
 *
 * <p>{@code password} 不在这里——即使存的是 BCrypt 密文，返回给前端也等于
 * 把离线爆破的原料交了出去。实体与出参分成两个类，正是为了让这种字段
 * 「不被返回」成为一种结构上的必然，而不是每次都要记得手动排除。
 */
@Data
public class UserInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private String email;

    private String studentNo;

    private String college;

    private String major;

    /** 入学年份，如 2023 */
    private Integer grade;

    /** 0未知 1男 2女 */
    private Integer gender;

    private String bio;

    /** 0普通用户 1版主 2管理员 */
    private Integer role;

    private Integer level;

    private Integer exp;

    private Integer postCount;

    private Integer followerCount;

    private Integer followingCount;

    private LocalDateTime createTime;
}
