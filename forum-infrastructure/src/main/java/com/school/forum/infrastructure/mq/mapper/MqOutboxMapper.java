package com.school.forum.infrastructure.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.infrastructure.mq.outbox.MqOutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表读写。
 */
@Mapper
public interface MqOutboxMapper extends BaseMapper<MqOutboxMessage> {

    /**
     * 扫描到期待投递的消息。
     *
     * <p>走 {@code idx_status_retry (status, next_retry_time)} 索引：
     * {@code status IN (0,2)} 是两个等值条件，{@code next_retry_time <= ?} 是范围条件，
     * 正好落在索引的第二个列上。用 {@code next_retry_time} 而不是 {@code create_time}
     * 做排序键，是因为延迟消息的投递时刻由它决定，与创建时刻无关。
     *
     * <p>{@code LIMIT} 是必须的：积压时一次全捞出来会把内存打满，
     * 而且长事务会长时间持锁，阻塞业务侧的写入。
     */
    @Select("""
            SELECT * FROM t_mq_message
            WHERE status IN (0, 2)
              AND next_retry_time <= #{now}
            ORDER BY next_retry_time
            LIMIT #{limit}
            """)
    List<MqOutboxMessage> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 抢占式更新状态，防止多实例重复投递。
     *
     * <p><b>为什么必须带 {@code AND status = #{expectStatus}}：</b>
     * 这是乐观锁。两个实例同时扫到同一条消息时，只有一个能把状态从 0 改成 1
     * （影响行数 = 1），另一个的影响行数是 0，据此就能判断「这条不归我发」。
     * 如果写成无条件的 UPDATE，两个实例都会认为抢到了，消息被重复投递两次。
     *
     * @return 影响行数。1 表示抢占成功，0 表示已被其他实例抢走
     */
    @Update("""
            UPDATE t_mq_message
            SET status = #{newStatus},
                retry_count = retry_count + #{retryDelta},
                error_msg = #{errorMsg},
                next_retry_time = #{nextRetryTime},
                update_time = NOW()
            WHERE id = #{id}
              AND status = #{expectStatus}
            """)
    int casUpdateStatus(@Param("id") Long id,
                        @Param("expectStatus") int expectStatus,
                        @Param("newStatus") int newStatus,
                        @Param("retryDelta") int retryDelta,
                        @Param("errorMsg") String errorMsg,
                        @Param("nextRetryTime") LocalDateTime nextRetryTime);
}
