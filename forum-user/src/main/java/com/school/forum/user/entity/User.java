package com.school.forum.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体，对应 {@code t_user}。
 *
 * <p><b>本类不得被 Controller 直接返回。</b>它含有 {@code password} 字段，
 * 虽然存的是 BCrypt 密文，但泄露出去等于把离线爆破的原料交给了攻击者。
 * 对外一律经 {@code vo} 包转换，这也是 {@code entity} 与 {@code vo} 分开的意义。
 *
 * <p>{@code role} 与 {@code status} 是两个独立维度，不能合并：
 * {@code role} 决定「能做什么」（普通用户/版主/管理员），
 * {@code status} 决定「账号还能不能用」（正常/禁言/封禁/注销）。
 * 一个管理员完全可能同时处于「禁言」状态——他被限制了发言权，但仍有管理权限。
 */
@Data
@TableName("t_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 状态与角色取值 ====================

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_MUTED = 1;
    public static final int STATUS_BANNED = 2;

    /** 已注销。用户名仍被占用，防止他人冒名注册 */
    public static final int STATUS_CANCELLED = 3;

    public static final int ROLE_USER = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文（固定 60 字符，字段留 100 位是给未来换算法留的余量） */
    private String password;

    private String nickname;

    private String avatar;

    private String email;

    private String phone;

    /** 学号。唯一索引允许 NULL，故未绑定学号的用户不会互相冲突 */
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

    /** 0正常 1禁言 2封禁 3注销 */
    private Integer status;

    /** 等级，由 exp 换算。与 exp 分开存是为了让等级规则可随时调整而不必改历史数据 */
    private Integer level;

    private Integer exp;

    /** 发帖数（冗余，最终一致） */
    private Integer postCount;

    private Integer followerCount;

    private Integer followingCount;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
