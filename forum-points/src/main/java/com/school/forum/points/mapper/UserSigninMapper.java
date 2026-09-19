package com.school.forum.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.points.entity.UserSignin;
import com.school.forum.points.support.SigninCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 签到记录数据访问。
 *
 * <p>两个自定义查询（当月日期集合、上月全勤用户）都需要聚合或范围条件，
 * {@code BaseMapper} 的通用方法覆盖不了，因此放在 XML 里——这也是本项目里
 * 「能注解就注解、注解写不下才用 XML」的一贯做法：
 * 单表条件更新用 {@code @Update} 更紧凑，多条件动态 SQL 用 XML 更可读。
 */
@Mapper
public interface UserSigninMapper extends BaseMapper<UserSignin> {

    /**
     * 查某用户某月已签到的全部日期。
     *
     * <p><b>为什么查整月而不是只查「今天签没签」：</b>前端要渲染签到月历，
     * 需要知道本月每一天的状态。用户一次打开页面就会要这个数据，
     * 而它一次查询就能拿全（一个月最多 31 行，走 {@code idx_month_user} 或
     * {@code uk_user_date} 的联合前缀）。反过来若按天逐个 {@code GETBIT}，
     * 就是 31 次 Redis 往返，还得再查一次库确认。
     *
     * @param yearMonth {@code yyyy-MM}
     */
    List<LocalDate> selectDatesOfMonth(@Param("userId") Long userId,
                                       @Param("yearMonth") String yearMonth);

    /**
     * 查上月签到天数超过阈值的用户。
     *
     * <p>走 {@code idx_month_user (year_month, user_id)} 覆盖索引：
     * 查询只需要这两列，不必回表读 {@code signin_date}、{@code points}。
     *
     * <p><b>{@code HAVING COUNT(*) > #{threshold}} 而不是 {@code >=}：</b>
     * 需求是「超过 25 天」，即 ≥ 26 天。这类边界差一的错误在测试里
     * 几乎发现不了（构造 25 天和 26 天的数据都很麻烦），所以阈值参数
     * 在配置里写成「超过多少」，而不是「达到多少」，让语义与代码一致。
     *
     * <p><b>分批用 {@code lastUserId} 游标而不是 {@code LIMIT offset}：</b>
     * 结算过程中会往 {@code t_points_record} 写数据，但本查询读的是
     * {@code t_user_signin}，结果集是稳定的，按 user_id 递增往下走即可。
     * {@code offset} 在深分页时要反复扫描前面已跳过的分组，而这批任务
     * 恰恰是要把整月符合条件的用户全部走完。
     *
     * @param lastUserId 上一批最后一个用户 ID，null 表示第一批
     * @param limit      单批上限。分批是为了让任务在用户量大时也保持短事务，
     *                   而不是一次把几万个用户的加分塞进一个事务
     */
    List<SigninCount> selectFullAttendanceUsers(@Param("yearMonth") String yearMonth,
                                                @Param("threshold") int threshold,
                                                @Param("lastUserId") Long lastUserId,
                                                @Param("limit") int limit);

    /**
     * 用户在上月的签到天数。
     *
     * <p>按 {@code (user_id, year_month)} 精确查询，供「我的签到」页显示
     * 「上月签到 N 天，距全勤还差 M 天」。走 {@code idx_month_user}。
     */
    int countByMonth(@Param("userId") Long userId, @Param("yearMonth") String yearMonth);

    /**
     * 某个日期之后（含）的全部签到记录，供位图修复任务重建位图。
     *
     * <p><b>为什么需要这个任务：</b>Redis 的数据可能在重启、误清、
     * 内存淘汰中丢失。位图不是真相，只是加速器，所以丢了不该导致任何数据错误——
     * 前提是它必须能被重建。本查询就是重建的数据来源：
     * {@code t_user_signin} 里有哪一天，位图就该是哪一天，没有第二种可能。
     *
     * <p>调用方（任务）按 {@code year_month} 分组后用 Pipeline 批量 {@code SETBIT}，
     * 而不是逐条一次往返。
     *
     * @param since 起始日期（含）。任务传「两个月前」即可覆盖当月与上月的统计需求
     */
    List<UserSignin> selectSigninSince(@Param("since") LocalDate since);
}
