package com.school.forum.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.points.entity.UserPoints;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 积分账户数据访问。
 *
 * <p>两个写方法都是<b>增量 / 条件 UPDATE</b>，没有一处是「先查再改」。
 * 这不是风格偏好，而是正确性问题：
 *
 * <ul>
 *   <li>{@code SET balance = balance + ?} 而不是 {@code SET balance = ?}——
 *       后者要求调用方先读到当前值。两个并发请求同时读到 100，
 *       各自算出 105 再写回，最终只加了 5 分，一次签到或一笔退回凭空消失。</li>
 *   <li>扣款带 {@code AND balance >= ?}——扣成负数在 {@code INT UNSIGNED} 列上
 *       会直接报错，在普通列上则会安静地变成负数。条件更新把「余额够不够」
 *       交给数据库在同一把行锁内判定，返回 0 行就是不够。</li>
 * </ul>
 */
@Mapper
public interface UserPointsMapper extends BaseMapper<UserPoints> {

    /**
     * 账户不存在时插入一条零余额记录。
     *
     * <p>用 {@code INSERT IGNORE} 而不是「先查后插」：后者在并发下会双写，
     * 由 {@code uk_user} 兜底时表现为抛异常；{@code INSERT IGNORE} 遇到冲突直接跳过，
     * 才是幂等路径该有的样子。
     *
     * <p>为什么需要这个「开户」动作：签到是用户的第一个积分行为，
     * 若账户行不存在，后面的条件更新会静默影响 0 行，表现为「签到了但没加分」。
     *
     * <p><b>注解必须是 {@code @Insert}，不能因为「它是条写语句」就写成 {@code @Update}。</b>
     * MyBatis 的 {@code SqlCommandType} 取自注解而不是 SQL 文本，而
     * {@code BlockAttackInnerInterceptor}（防全表更新/删除）只对
     * {@code UPDATE}/{@code DELETE} 两种类型做解析。写成 {@code @Update} 的话，
     * 它会把这条语句当成 UPDATE 送进 JSqlParser，解析出 Insert 节点后
     * 落到默认实现上直接抛 {@code UnsupportedOperationException}——
     * 报错信息里只有一句「不支持的操作」，与 INSERT IGNORE 毫无字面关联，
     * 极难反查。
     */
    @Insert("INSERT IGNORE INTO t_user_points (user_id, balance, total_earned, total_spent) "
            + "VALUES (#{userId}, 0, 0, 0)")
    int initAccount(@Param("userId") Long userId);

    /**
     * 增加积分：签到、月度全勤奖励、兑换取消退回。
     *
     * <p><b>退回走的是同一个方法，这不是偷懒而是账要这么做。</b>
     * 三个字段的关系被定义为
     * {@code totalEarned = 全部正数流水之和}、{@code totalSpent = 全部负数流水之和}，
     * 于是 {@code balance = totalEarned - totalSpent} 恒成立，
     * 且等于 {@code SUM(change_amount)}。退回是一笔正数流水，自然就计入 {@code totalEarned}。
     *
     * <p>反过来若「退回只加 balance、不改 totalEarned」，这条不变式立刻被打破，
     * §9 的两条对账查询会持续报错。若为此再反过来减 {@code totalSpent}，
     * 则「累计消耗过 100 分」这个事实会被抹掉——历史不该因为后来的退款被改写。
     *
     * @return 影响行数。调用方需断言为 1，否则说明账户行不存在
     */
    @Update("UPDATE t_user_points "
            + "SET balance = balance + #{amount}, total_earned = total_earned + #{amount} "
            + "WHERE user_id = #{userId}")
    int addBalance(@Param("userId") Long userId, @Param("amount") int amount);

    /**
     * 扣减积分（商城兑换）。
     *
     * <p><b>这是防「扣成负数」的唯一手段。</b>
     *
     * @return 影响行数。0 表示余额不足，调用方应返回 16002 并回滚事务
     */
    @Update("UPDATE t_user_points "
            + "SET balance = balance - #{amount}, total_spent = total_spent + #{amount} "
            + "WHERE user_id = #{userId} AND balance >= #{amount}")
    int deductBalance(@Param("userId") Long userId, @Param("amount") int amount);

    /**
     * 查当前余额。
     *
     * <p><b>只该在写完之后调用。</b>流水的 {@code balance_after} 快照要求的是
     * 「这笔变动之后」的余额，而 {@code deductBalance} 的返回值只有影响行数，
     * 算不出新余额。先读后算（{@code 读到的值 - amount}）在并发下会算错——
     * 两个请求各自读到 100，都以为变动后是 95，实际有一个是 90。
     * 写完再读才是准的：{@code UPDATE} 已经持有该行的排他锁直到事务结束，
     * 事务内的下一次读取必然看到自己刚写下的值。
     *
     * @return 账户不存在时返回 null
     */
    @Select("SELECT balance FROM t_user_points WHERE user_id = #{userId}")
    Integer selectBalance(@Param("userId") Long userId);
}
