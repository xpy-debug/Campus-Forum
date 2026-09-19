package com.school.forum.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 签到记录，对应 {@code t_user_signin}。
 *
 * <p><b>本表是签到行为的最终真相</b>，Redis 位图只是它前面的加速器。
 * 两者的分工见《02-架构设计》ADR-007：位图负责「今天签了吗」的廉价判断与
 * 「本月签了几天」的廉价统计，本表负责在任何缓存都失效之后仍能说清事实。
 *
 * <p>{@code uk_user_date} 保证同一天不会有两行，因此重复请求最多让位图多一次
 * {@code SETBIT}，绝不会多发积分——这是整个签到链路里唯一不会失效的一层。
 */
@Data
@TableName("t_user_signin")
public class UserSignin implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate signinDate;

    /**
     * 所属年月 {@code yyyy-MM}，冗余列。
     *
     * <p>月度全勤结算是**每天都要跑**的查询。若写成
     * {@code WHERE DATE_FORMAT(signin_date, '%Y-%m') = ?}，函数会让索引失效，
     * 这个查询就退化成全表扫描，而表里最终会有上千万行。
     *
     * <p><b>{@code YEAR_MONTH} 是 MySQL 的保留字</b>（它是 INTERVAL 的单位之一，
     * 形如 {@code INTERVAL 1 YEAR_MONTH}），裸写列名会直接语法错误。
     * 这里在 {@code @TableField} 里带上反引号，让 MyBatis-Plus 生成的
     * INSERT / SELECT 也带上转义——若只改 XML 而漏掉这里，
     * 签到落库就会在 {@code insert} 这一步炸掉。
     * 手写 SQL 里同样必须写 {@code `year_month`}。
     */
    @TableField("`year_month`")
    private String yearMonth;

    /** 当次获得的积分。存入当次的数值而不是引用当前配置，历史记录才不会被后来的调参改写 */
    private Integer points;

    private LocalDateTime createTime;
}
