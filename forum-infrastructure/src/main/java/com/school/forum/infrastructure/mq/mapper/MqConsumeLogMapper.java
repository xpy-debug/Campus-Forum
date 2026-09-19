package com.school.forum.infrastructure.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.infrastructure.mq.metrics.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消费埋点写入。
 */
@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    /**
     * 批量 upsert 消费记录。
     *
     * <p><b>为什么必须是 upsert 而不是普通 INSERT：</b>
     * 表上有唯一键 {@code uk_msg_consumer (message_id, consumer_group)}。
     * 一条消息重试时用普通 INSERT 会直接抛 Duplicate entry，
     * 导致整批埋点失败（批量插入是一条 SQL，一行冲突整批回滚）。
     * 用 {@code ON DUPLICATE KEY UPDATE} 把重试合并进同一行，
     * 既绕开了冲突，又让「一条消息一行」的语义成立。
     *
     * <p><b>各字段的合并策略：</b>
     * <ul>
     *   <li>{@code status} —— 覆盖为最新一次的结果</li>
     *   <li>{@code retry_count} —— 覆盖为最新一次的重试序号</li>
     *   <li>{@code cost_ms} —— <b>累加</b>，得到这条消息最终处理成功所花的总耗时</li>
     *   <li>{@code error_msg} —— 覆盖，保留最近一次的错误信息</li>
     *   <li>{@code create_time} —— <b>不更新</b>，保持首次消费时间，避免时间聚合出现重影</li>
     * </ul>
     *
     * @param list 待写入记录，调用方需保证同一批内没有重复的 (message_id, consumer_group)。
     *             <p><b>注意：</b>MySQL 的 ON DUPLICATE KEY UPDATE 在<b>同一条 INSERT 语句内</b>
     *             遇到两行相同唯一键时也会报错，所以 {@code MqMetricsRecorder} 在入队前
     *             会用 {@code LinkedHashMap} 按 messageId+group 去重。
     */
    @Insert("""
            <script>
            INSERT INTO t_mq_consume_log
                (message_id, consumer_group, provider, topic, biz_type,
                 status, retry_count, cost_ms, error_msg, create_time)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.messageId}, #{it.consumerGroup}, #{it.provider}, #{it.topic}, #{it.bizType},
                 #{it.status}, #{it.retryCount}, #{it.costMs}, #{it.errorMsg}, #{it.createTime})
            </foreach>
            ON DUPLICATE KEY UPDATE
                status      = VALUES(status),
                retry_count = VALUES(retry_count),
                cost_ms     = cost_ms + VALUES(cost_ms),
                error_msg   = VALUES(error_msg)
            </script>
            """)
    int upsertBatch(@Param("list") List<MqConsumeLog> list);
}
