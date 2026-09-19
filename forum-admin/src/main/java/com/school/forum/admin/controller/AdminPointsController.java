package com.school.forum.admin.controller;

import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.auth.LoginUser;
import com.school.forum.infrastructure.web.auth.RequireRole;
import com.school.forum.points.api.PointsAdminApi;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分管理接口。上下文路径为 {@code /api}，故实际路径是 {@code /api/admin/points/*}。
 *
 * <p>目前只有「手动结算签到奖励」一个能力，但它是必需的：
 * 自动任务漏跑（发版撞上调度时间、Redis 连不上导致抢锁失败）时，
 * 运营需要一个能自己按一下的地方，而不是提工单让开发去改数据库。
 * <b>「自动任务 + 可手动补跑」才是「任务漏跑」这一故障模式的完整解法。</b>
 */
@RestController
@RequestMapping("/admin/points")
@RequireRole(LoginUser.ROLE_ADMIN)
@RequiredArgsConstructor
public class AdminPointsController {

    private final PointsAdminApi pointsAdminApi;

    /**
     * 手动结算某个月的签到全勤奖励。
     *
     * <p><b>幂等，可以放心重试。</b>已经发放过的用户会被唯一索引与流水查询挡掉，
     * 因此「不确定上次跑没跑过」时直接再调一次即可，不必先去查。
     * 返回的是**本次实际发放的人数**，为 0 说明该发的都发过了，而不是失败了。
     *
     * <p>传入当前月份或未来月份返回 0 且不做任何发放：那些月份的签到数据还未定型。
     *
     * <p>查询参数名是 {@code month} 而不是 {@code yearMonth}：这是《01-需求分析》
     * UC-POINTS-02 与《03-功能设计》管理接口清单里写定的契约，
     * 接口对外的那一层以文档为准，内部变量名才叫 {@code yearMonth}。
     *
     * @param yearMonth 结算月份 {@code yyyy-MM}，例如 {@code 2026-08}
     * @return 本次发放奖励的用户数
     */
    @PostMapping("/settle-signin-bonus")
    public Result<Integer> settleSigninBonus(@RequestParam("month") String yearMonth) {
        return Result.ok(pointsAdminApi.settleSigninBonus(yearMonth));
    }
}
