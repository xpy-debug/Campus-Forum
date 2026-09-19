package com.school.forum.points.vo;

import com.school.forum.points.entity.PointsRecord;

import java.time.LocalDateTime;

/**
 * 一条积分流水。
 *
 * <p>{@code bizTypeName} 由后端翻译成中文，而不是让前端维护一份映射表：
 * 业务类型是**后端的概念**（哪几种行为会给积分由运营决定），
 * 前端维护映射意味着后端加一种类型就要同步改前端，而且漏改时表现为空白而不是报错。
 *
 * @param id           流水 ID。前端用它做列表 key，游标分页也基于它
 * @param changeAmount 变动量，正负号本身就是信息
 * @param balanceAfter 变动后余额
 * @param bizType      业务类型码
 * @param bizTypeName  业务类型中文名
 * @param bizId        业务标识（签到日期 / 月份 / 订单号）
 * @param remark       备注
 * @param createTime   发生时间
 */
public record PointsRecordVO(Long id,
                             Integer changeAmount,
                             Integer balanceAfter,
                             Integer bizType,
                             String bizTypeName,
                             String bizId,
                             String remark,
                             LocalDateTime createTime) {

    public static PointsRecordVO of(PointsRecord record) {
        if (record == null) {
            return null;
        }
        return new PointsRecordVO(
                record.getId(), record.getChangeAmount(), record.getBalanceAfter(),
                record.getBizType(), bizTypeName(record.getBizType()),
                record.getBizId(), record.getRemark(), record.getCreateTime());
    }

    /**
     * 业务类型的中文名。
     *
     * <p>写成 switch 表达式而不是查表：新增类型时若漏了分支，
     * switch 的穷尽性检查（配合 {@code default} 下的显式兜底）比查表更容易被发现。
     * 兜底返回「积分变动」而不是 null，保证界面永远有一句能读的话。
     */
    private static String bizTypeName(Integer bizType) {
        if (bizType == null) {
            return "积分变动";
        }
        return switch (bizType) {
            case PointsRecord.BIZ_SIGNIN -> "每日签到";
            case PointsRecord.BIZ_MONTHLY_BONUS -> "月度全勤奖励";
            case PointsRecord.BIZ_MALL_EXCHANGE -> "积分兑换";
            case PointsRecord.BIZ_MALL_REFUND -> "兑换取消退回";
            case PointsRecord.BIZ_ADMIN_ADJUST -> "管理员调整";
            default -> "积分变动";
        };
    }
}
