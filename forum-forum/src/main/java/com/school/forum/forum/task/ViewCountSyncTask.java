package com.school.forum.forum.task;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.forum.mapper.PostMapper;
import com.school.forum.forum.support.TargetDelta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 浏览数回写。
 *
 * <p>详情页只把浏览记在 Redis（{@code INCR forum:post:view:{postId}}），
 * 本任务定期把它们批量加回 {@code t_post.view_count}。
 *
 * <p><b>为什么不直接每次浏览都 UPDATE：</b>那会让每次浏览都产生一次写操作并持有行锁，
 * 热门帖子上就是排队等锁——而浏览数是全站最不精确也不重要的计数之一，
 * 为它付出写放大并不划算。
 *
 * <p><b>为什么用 {@code DECRBY} 而不是 {@code DEL} 清空计数键：</b>读值与清空之间
 * 总有时间窗，窗口内新来的浏览会被 {@code DEL} 一并抹掉。按读到的增量减去，
 * 窗口内的新浏览会留在键里，等下一轮处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountSyncTask {

    /** 单轮最多回写这么多帖子，避免一次大查询把回写时间拉得过长 */
    private static final int BATCH_SIZE = 200;

    /** 计数键的空闲寿命，与详情页写入时的取值保持一致 */
    private static final Duration COUNTER_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final PostMapper postMapper;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${forum.task.view-sync-interval-ms:300000}")
    public void sync() {
        RLock lock = redissonClient.getLock(RedisKey.jobLock("post-view-sync"));
        boolean locked = false;
        try {
            // 与本地消息表调度器同样的取舍：抢不到锁就跳过本轮，不排队等待。
            // 多实例部署时这保证只有一个实例在回写，避免同一批增量被写两次
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            doSync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("浏览数回写异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doSync() {
        // SPOP 而不是 SMEMBERS + SREM：SPOP 把「取出」与「移出」合成一次原子操作，
        // 天然完成了「认领」。用两步的话，两个实例可能同时取到同一个帖子
        List<String> claimed = redis.opsForSet().pop(RedisKey.postViewDirty(), BATCH_SIZE);
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        List<TargetDelta> deltas = collect(claimed);
        if (deltas.isEmpty()) {
            return;
        }

        try {
            postMapper.batchAddViewCount(deltas);
        } catch (Exception e) {
            compensate(deltas);
            throw e;
        }
        // 先写库再减计数：若在两者之间进程挂掉，最坏结果是这批浏览数多加一次；
        // 反过来（先减后写）挂掉就是这批浏览数永远丢失。浏览量宁可多算不可少算
        release(deltas);
    }

    /** 读出每个帖子尚未回写的增量。读不到（键已过期）说明没有增量，跳过 */
    private List<TargetDelta> collect(List<String> claimed) {
        List<TargetDelta> deltas = new ArrayList<>(claimed.size());
        for (String raw : claimed) {
            Long postId = parseLong(raw);
            if (postId == null) {
                continue;
            }
            Long cached = parseLong(redis.opsForValue().get(RedisKey.postView(postId)));
            long delta = cached == null ? 0L : cached;
            if (delta <= 0) {
                continue;
            }
            // 增量按 int 传递，极端情况下溢出时截断而不是回绕成负数
            deltas.add(new TargetDelta(postId, (int) Math.min(delta, Integer.MAX_VALUE)));
        }
        return deltas;
    }

    /** 减去已回写的增量；窗口内新产生的浏览重新入队，等下一轮处理 */
    private void release(List<TargetDelta> deltas) {
        for (TargetDelta delta : deltas) {
            String key = RedisKey.postView(delta.getTargetId());
            Long remaining = redis.opsForValue().decrement(key, delta.getDelta());
            redis.expire(key, COUNTER_TTL);
            if (remaining != null && remaining > 0) {
                redis.opsForSet().add(RedisKey.postViewDirty(), String.valueOf(delta.getTargetId()));
            }
        }
    }

    /**
     * 回写失败时把增量还回去。
     *
     * <p>帖子已被 SPOP 移出待回写集合，若不重新入队，这批浏览数就再也不会被处理——
     * 计数键里挂着数字，却没有任何任务会读到它。
     */
    private void compensate(List<TargetDelta> deltas) {
        for (TargetDelta delta : deltas) {
            String key = RedisKey.postView(delta.getTargetId());
            redis.opsForValue().increment(key, delta.getDelta());
            redis.expire(key, COUNTER_TTL);
            redis.opsForSet().add(RedisKey.postViewDirty(), String.valueOf(delta.getTargetId()));
        }
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            // 集合里混进了非数字成员，跳过即可。让它一直留着没有意义，
            // 下次 SPOP 时仍会被取出并再次跳过
            return null;
        }
    }
}
