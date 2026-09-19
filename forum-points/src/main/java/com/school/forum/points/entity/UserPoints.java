package com.school.forum.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分账户，对应 {@code t_user_points}。
 *
 * <p><b>不变式：{@code balance = totalEarned - totalSpent}。</b>
 * 三个字段互相冗余，但冗余的方向是刻意的：{@code balance} 服务于高频读
 * （每次进积分页、每次下单校验），后两者服务于「这一年一共攒了多少、花了多少」
 * 这类统计——两者都要，而从任一方推导另一方都需要扫描流水。
 *
 * <p><b>为什么不做成 {@code t_user} 的一列：</b>见 {@code t_user_points} 的建表注释
 * 与《04-数据库设计》4.10 节，核心是行锁竞争与事务边界两件事。
 *
 * <p>本类不带 {@code version} 乐观锁列：余额的并发安全由
 * {@code WHERE balance >= ?} 条件更新保证（失败即积分不足），
 * 这比「读版本号 → 改 → 校验版本号失败后重试」少一次往返，也没有重试风暴。
 */
@Data
@TableName("t_user_points")
public class UserPoints implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 可用积分余额 */
    private Integer balance;

    /**
     * 累计获得积分 = 全部正数流水之和（签到、月度奖励、兑换退回）。
     *
     * <p>只增不减。退回也计入此列而不是去冲减 {@code totalSpent}——
     * 「累计消耗过 100 分」是一个事实，不该被后来的退款改写。
     */
    private Integer totalEarned;

    /** 累计消耗积分 = 全部负数流水之和。只增不减 */
    private Integer totalSpent;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
