package com.school.forum.notification.api;

import java.io.Serializable;

/**
 * 自检结果。
 *
 * <p>用 {@code record} 而不是普通类：它是不可变的只读快照，
 * 没有行为、不需要 setter，record 天然表达了这一点，
 * 同时自动获得 {@code equals} / {@code hashCode} / {@code toString}，少写几十行样板代码。
 *
 * <p>字段设计上刻意把「发送侧」和「消费侧」的信息都放进来，
 * 因为自检最常见的问题是「发出去了但没收到」，
 * 这时需要一眼看出是根本没发出去，还是发了但没被消费。
 *
 * @param provider    当前生效的 MQ 实现（kafka / rabbit）
 * @param eventId     本次自检消息的 ID
 * @param note        调用方附带的说明
 * @param consumed    消息是否已被消费。false 表示在等待窗口内没等到——
 *                    通常是消费者没起来、主题/队列名不匹配、或者消费者组配错
 * @param e2eMillis   端到端耗时。仅当 consumed 为 true 时有意义
 * @param receivedAt  消费时刻的可读时间
 * @param totalCount  自检消息累计消费条数，用于确认消费端确实在工作
 */
public record SelfTestResult(
        String provider,
        String eventId,
        String note,
        boolean consumed,
        long e2eMillis,
        String receivedAt,
        long totalCount) implements Serializable {
}
