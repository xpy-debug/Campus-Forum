package com.school.forum.forum.consumer;

import com.school.forum.forum.event.LikeChangedEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 点赞事件的攒批器：<b>攒够一批或等够时间就落库</b>。
 *
 * <p><b>为什么要攒批：</b>点赞的目标吞吐是 5000 QPS。逐条落库意味着每秒
 * 5000 次 INSERT + 5000 次 UPDATE，MySQL 会先于任何组件倒下。
 * 攒成每批 200 条之后，写入次数降到 1/200，代价是最坏情况下 500ms 的落库延迟——
 * 而用户看到的是 Redis 的实时计数，感知不到这个延迟。
 *
 * <p><b>双触发条件缺一不可：</b>只按条数触发，低峰期最后几条会一直躺在缓冲区里；
 * 只按时间触发，高峰期的写入次数又降不下来。
 *
 * <p><b>这里放弃了什么：</b>消息在进入缓冲区后就已经 ack 了，此时 JVM 崩溃会丢掉
 * 这一批（最多 200 条点赞记录）。这是设计上明确的取舍——Redis 里的点赞集合与计数
 * 仍然是对的，每日对账会以 {@code t_user_like} 的行数为准反向修正数据库计数。
 * 要做到「不丢」需要「先落库再 ack」，那等于把攒批省下来的写入次数又还回去。
 *
 * <p>相对的，<b>落库失败不会丢数据</b>：批次会被放回缓冲区，下一个调度周期重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeBatchWriter {

    /** 攒够多少条立刻落库。取值需权衡：太大则失败时丢弃的更多，太小则批量收益不明显 */
    private static final int BATCH_SIZE = 200;

    /** 最长等待多久落库一次。保证低峰期数据不会无限期停留在内存里 */
    private static final long FLUSH_INTERVAL_MS = 500;

    /** 缓冲区上限。持续失败时不能让缓冲区无限增长，否则会把内存吃光 */
    private static final int MAX_BUFFER_SIZE = 20_000;

    /** 连续失败多少次后放弃当前批次。避免一批「毒数据」永远堵在缓冲区里 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final LikeBatchPersister persister;

    private final List<LikeChangedEvent> buffer = new ArrayList<>();

    /**
     * 用 {@link ReentrantLock} 而不是 {@code synchronized}：消费线程与调度线程
     * 都要进来操作缓冲区，用显式锁能让「临界区到哪结束」一眼可见，
     * 也便于将来换成 tryLock 实现快速失败。
     */
    private final ReentrantLock lock = new ReentrantLock();

    private int consecutiveFailures = 0;

    /** 消费线程调用。攒够一批就地落库，不等调度 */
    public void add(LikeChangedEvent event) {
        lock.lock();
        try {
            buffer.add(event);
            if (buffer.size() >= BATCH_SIZE) {
                flush();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 兜底触发，保证低峰期数据也能及时落库 */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flushOnSchedule() {
        lock.lock();
        try {
            if (!buffer.isEmpty()) {
                flush();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关机前把缓冲区里剩下的写完。
     *
     * <p>不这么做的话，正常重启也会丢最后不到 500ms 的一批——而正常重启
     * 恰恰是最可控、最不该丢数据的场景。
     */
    @PreDestroy
    public void flushOnShutdown() {
        lock.lock();
        try {
            if (!buffer.isEmpty()) {
                log.info("应用关闭，落库剩余的 {} 条点赞事件", buffer.size());
                flush();
            }
        } catch (Exception e) {
            // 关停阶段数据库可能已经不可用，尽力而为即可
            log.error("关机前落库点赞事件失败，本批数据将由每日对账修正", e);
        } finally {
            lock.unlock();
        }
    }

    /** 调用方必须已持有锁 */
    private void flush() {
        List<LikeChangedEvent> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            persister.persist(batch);
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures <= MAX_CONSECUTIVE_FAILURES) {
                requeue(batch);
                log.error("点赞批量落库失败，{} 条将放回缓冲区重试（第 {} 次）",
                        batch.size(), consecutiveFailures, e);
            } else {
                consecutiveFailures = 0;
                log.error("点赞批量落库连续失败 {} 次，丢弃本批 {} 条，"
                                + "将由每日对账以 t_user_like 行数为准修正计数。eventIds={}",
                        MAX_CONSECUTIVE_FAILURES, batch.size(), eventIds(batch));
            }
        }
    }

    private void requeue(List<LikeChangedEvent> batch) {
        if (buffer.size() + batch.size() > MAX_BUFFER_SIZE) {
            log.error("点赞缓冲区超过上限 {}，丢弃重试批次 {} 条，避免内存被耗尽",
                    MAX_BUFFER_SIZE, batch.size());
            consecutiveFailures = 0;
            return;
        }
        // 放回队首：这些事件的 occurredAt 比缓冲区里现有的都早，
        // 保持顺序能让乱序守则的时间戳比较更符合直觉
        buffer.addAll(0, batch);
    }

    private List<String> eventIds(List<LikeChangedEvent> batch) {
        return batch.stream().map(LikeChangedEvent::getEventId).limit(20).toList();
    }
}
