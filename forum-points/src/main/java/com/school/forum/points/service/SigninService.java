package com.school.forum.points.service;

import com.school.forum.points.vo.SigninResultVO;
import com.school.forum.points.vo.SigninStatusVO;

/**
 * 每日签到。
 *
 * <p>签到是本模块唯一的「高频 + 天然幂等」动作：每个用户每天最多生效一次，
 * 但可能被点击很多次（连点、网络重试、页面刷新后重放）。
 * 因此它用位图而不是计数器来实现——见《02-架构设计》ADR-007。
 */
public interface SigninService {

    /** 签到页状态：整月日历、连续天数、全勤进度 */
    SigninStatusVO status(Long userId);

    /**
     * 签到。
     *
     * <p><b>已经签过不报错</b>，返回 {@code success=false} 的结果对象。
     * 详见 {@link SigninResultVO}。
     */
    SigninResultVO signin(Long userId);
}
