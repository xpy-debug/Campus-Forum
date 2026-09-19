package com.school.forum.points.vo;

/**
 * 签到的结果。
 *
 * <p><b>重复签到不返回错误。</b>「今天已经签过了」是一个正常状态，
 * 而不是异常——用户隔了几小时又点了一次按钮，或者前端连点两下，
 * 得到的应该是「已签到」这个事实，而不是一条红色报错。
 * 因此这里返回 {@code success=false} 而不是抛 16001 异常：
 * 用 {@code Result} 包装一层异常只是把同一个语义表达得更绕。
 *
 * <p>{@code Result.code} 仍是 0（请求本身处理成功），
 * 由 {@code success} 字段表达业务结果。这与「错误码 16001」并不矛盾：
 * 16001 留给「明确要区分失败原因」的调用方（例如将来对接外部系统时），
 * 而普通前端只需要这个字段。
 *
 * @param success      本次是否真的签上了
 * @param points       本次获得的积分。已签过时为 0
 * @param balance      操作后的积分余额。让前端不必再发一次请求去刷新
 * @param signedDays   本月累计签到天数
 * @param message      给用户看的一句话
 */
public record SigninResultVO(boolean success,
                             int points,
                             int balance,
                             int signedDays,
                             String message) {
}
