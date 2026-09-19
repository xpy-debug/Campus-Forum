package com.school.forum.forum.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.MqTopic;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.forum.entity.Comment;
import com.school.forum.forum.entity.Post;
import com.school.forum.forum.entity.UserLike;
import com.school.forum.forum.event.LikeChangedEvent;
import com.school.forum.forum.mapper.CommentMapper;
import com.school.forum.forum.mapper.PostMapper;
import com.school.forum.forum.mapper.UserLikeMapper;
import com.school.forum.forum.service.LikeService;
import com.school.forum.forum.vo.LikeVO;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import com.school.forum.infrastructure.mq.core.MqProperties;
import com.school.forum.infrastructure.mq.mapper.MqOutboxMapper;
import com.school.forum.infrastructure.mq.outbox.MqOutboxMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 点赞的实现。整条链路分两段：
 *
 * <pre>
 * 同步段（本类）：Lua 原子改 Redis → 同一个事务里写一行 t_mq_message → 立刻返回
 * 异步段（{@link com.school.forum.forum.consumer.LikeBatchPersister}）：
 *                MqOutboxDispatcher 投递 → 消费端批量落库并更新计数
 * </pre>
 *
 * <p><b>为什么不能在这一步直接写 MySQL：</b>点赞是本系统写入最密集的动作，
 * 目标吞吐 5000 QPS。逐条 INSERT + UPDATE 会让 MySQL 先于任何组件崩掉，
 * 而且每次点赞都要等两次数据库往返才能给用户反馈。改成「Redis 立即生效 +
 * 消息异步落库」之后，接口耗时从毫秒级数据库写入压缩到一次 Lua 调用。
 *
 * <p><b>为什么必须写本地消息表：</b>{@code EventPublisher} 是「发出去就算成功」，
 * 不做任何持久化——进程在发消息前崩溃，这次点赞在 Redis 里已经生效，
 * 但数据库永远不会知道。把待发消息和业务动作放进同一个本地事务，
 * 才能保证「Redis 变了」和「消息一定会被发出」这两件事绑定在一起。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    /** 点赞脚本。脚本内容在类初始化时读入内存，避免每次调用都读一次文件 */
    private static final RedisScript<Long> LIKE_SCRIPT = loadScript("lua/like.lua");
    private static final RedisScript<Long> UNLIKE_SCRIPT = loadScript("lua/unlike.lua");

    private final StringRedisTemplate redis;
    private final UserLikeMapper userLikeMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final MqOutboxMapper outboxMapper;
    private final EventPublisher eventPublisher;
    private final MqProperties mqProperties;
    private final ObjectMapper objectMapper;

    // ==================== 点赞 / 取消点赞 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LikeVO like(Long userId, Integer targetType, Long targetId) {
        return change(userId, targetType, targetId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LikeVO unlike(Long userId, Integer targetType, Long targetId) {
        return change(userId, targetType, targetId, false);
    }

    private LikeVO change(Long userId, Integer targetType, Long targetId, boolean liked) {
        requireValidTargetType(targetType);
        Target target = resolveTarget(targetType, targetId);

        String usersKey = RedisKey.likeUsers(targetType, targetId);
        String countKey = RedisKey.likeCount(targetType, targetId);

        // 基准值只在计数键缺失时才会被用到（冷启动播种）。这里先查出来传给脚本，
        // 是因为 Lua 里没法查数据库——把「查库」和「原子改计数」拆成两步，
        // 只在前者确实必要时才付出一次数据库查询的代价
        long base = currentCountOrSeed(targetType, targetId, countKey);

        Long changed = redis.execute(liked ? LIKE_SCRIPT : UNLIKE_SCRIPT,
                List.of(usersKey, countKey),
                String.valueOf(userId),
                String.valueOf(base));
        boolean effective = changed != null && changed == 1L;

        if (effective) {
            // 乱序守卫的时间戳必须在消息可见之前写好。
            // 消息要经过「事务提交 → 调度器扫描（≥1 秒）→ 投递」才可能被消费，
            // 所以这里的先后顺序天然成立，不需要额外同步
            markLikeTimestamp(targetType, targetId, userId);
            publishEvent(userId, targetType, targetId, target.ownerId(), liked);
        }

        return new LikeVO(targetType, targetId, liked, readCount(countKey, base));
    }

    /**
     * 校验目标是否存在，并取出它的作者。
     *
     * <p>直接查 Mapper 而不调 {@code PostService}：后者为了组装列表已经依赖了本类，
     * 反过来再依赖它就成了循环依赖。这里需要的只是「这一行还在不在、作者是谁」，
     * 一条主键查询足够。
     */
    private Target resolveTarget(int targetType, Long targetId) {
        if (targetType == UserLike.TARGET_POST) {
            Post post = postMapper.selectById(targetId);
            if (post == null || post.getStatus() != Post.STATUS_PUBLISHED) {
                throw new BizException(ErrorCode.LIKE_TARGET_INVALID);
            }
            return new Target(post.getUserId());
        }
        Comment comment = commentMapper.selectById(targetId);
        if (comment == null || comment.getStatus() != Comment.STATUS_NORMAL) {
            throw new BizException(ErrorCode.LIKE_TARGET_INVALID);
        }
        return new Target(comment.getUserId());
    }

    // ==================== 计数与状态查询 ====================

    @Override
    public Map<Long, Long> mergeLikeCounts(int targetType, Map<Long, Long> dbCounts) {
        if (dbCounts == null || dbCounts.isEmpty()) {
            return Map.of();
        }
        List<String> keys = dbCounts.keySet().stream()
                .map(id -> RedisKey.likeCount(targetType, id))
                .toList();
        // MGET 一次往返取回全部计数键。逐条 GET 的话，20 条帖子就是 20 次 RTT
        List<String> values = redis.opsForValue().multiGet(keys);

        Map<Long, Long> merged = new HashMap<>(dbCounts.size());
        int index = 0;
        for (Map.Entry<Long, Long> entry : dbCounts.entrySet()) {
            String cached = values == null ? null : values.get(index++);
            Long value = parseLongOrNull(cached);
            // 键不存在时保留数据库的值，而不是当作 0——Redis 丢过一次数据就显示
            // 全部归零，是比「数字稍有滞后」严重得多的问题
            merged.put(entry.getKey(), value == null ? entry.getValue() : value);
        }
        return merged;
    }

    @Override
    public Set<Long> likedTargets(Long userId, int targetType, Collection<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = List.copyOf(targetIds);
        // 用 RedisCallback 而不是 SessionCallback：后者的 execute 方法是泛型方法，
        // 泛型方法无法用 lambda 实现（Java 不允许为泛型 SAM 生成 lambda），
        // 只能写成冗长的匿名内部类
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            RedisSerializer<String> serializer = RedisSerializer.string();
            byte[] member = serializer.serialize(String.valueOf(userId));
            for (Long id : ids) {
                byte[] key = serializer.serialize(RedisKey.likeUsers(targetType, id));
                connection.setCommands().sIsMember(key, member);
            }
            // 回调必须返回 null：管道模式下命令只是被排进队列，
            // 真正的结果由 executePipelined 统一收集后返回
            return null;
        });

        Set<Long> liked = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                liked.add(ids.get(i));
            }
        }
        return liked;
    }

    // ==================== 内部方法 ====================

    private void markLikeTimestamp(int targetType, Long targetId, Long userId) {
        String key = RedisKey.likeTimestamp(targetType, targetId, userId);
        redis.opsForValue().set(key, String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 写入本地消息表。真正的投递由 {@code MqOutboxDispatcher} 完成。
     *
     * <p>topic 按动作分开（点赞 {@code forum.like}、取消 {@code forum.unlike}），
     * 与设计文档一致。这也意味着两类事件之间没有顺序保证——顺序由事件里的
     * 时间戳 + 消费端守卫来兜底，而不是靠 MQ。
     *
     * <p>如果换成同一个 topic，仅靠 {@code routingKey}（目标）就能天然保序，
     * 守卫也就不必要了；代价是两类事件共享同一批分区的吞吐。
     * 这里保留两个 topic 是为了与设计文档和压测口径一致。
     */
    private void publishEvent(Long userId, int targetType, Long targetId, Long ownerId, boolean liked) {
        LikeChangedEvent event = new LikeChangedEvent();
        event.setUserId(userId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setTargetOwnerId(ownerId);
        event.setLiked(liked);

        MqOutboxMessage message = new MqOutboxMessage();
        message.setMessageId(event.getEventId());
        message.setProvider(eventPublisher.provider());
        message.setTopic(liked ? MqTopic.LIKE : MqTopic.UNLIKE);
        message.setRoutingKey(event.routingKey());
        message.setBizType(LikeChangedEvent.class.getSimpleName());
        message.setBizKey(bizKey(userId, targetType, targetId));
        message.setPayload(toJson(event));
        message.setHeaders("{}");
        message.setStatus(MqOutboxMessage.STATUS_PENDING);
        message.setRetryCount(0);
        message.setMaxRetry(mqProperties.getOutbox().getMaxRetry());
        // next_retry_time 是调度器的扫描条件，必须给当前时间而不是留空，
        // 否则 selectDue 的 `next_retry_time <= now` 永远不成立，消息发不出去
        message.setNextRetryTime(LocalDateTime.now());
        message.setErrorMsg("");
        outboxMapper.insert(message);
    }

    /**
     * 取实时计数；计数键不存在时用数据库的行数作为基准。
     *
     * <p><b>已知的边界情形：</b>若 Redis 整个实例的数据都没了（重启且未持久化），
     * 点赞集合与计数键会一起消失。此时按行数播种计数是准的，但如果该用户此前
     * 已经点过赞，{@code SADD} 会认为这是一次新点赞从而多加 1，直到每日对账
     * （以 {@code t_user_like} 行数为准）才会修正。这是设计上接受的取舍：
     * 与其在冷启动路径上再查一次全量点赞用户列表，不如让每日对账兜底。
     */
    private long currentCountOrSeed(int targetType, Long targetId, String countKey) {
        Long cached = parseLongOrNull(redis.opsForValue().get(countKey));
        if (cached != null) {
            return cached;
        }
        try {
            return userLikeMapper.countByTarget(targetType, targetId);
        } catch (DataAccessException e) {
            // 播种失败不该让点赞直接不可用：计数从 0 开始累积，同样由对账修正
            log.error("点赞计数播种失败，本次以 0 为基准。targetType={}, targetId={}", targetType, targetId, e);
            return 0L;
        }
    }

    private long readCount(String countKey, long fallback) {
        Long value = parseLongOrNull(redis.opsForValue().get(countKey));
        return value == null ? fallback : value;
    }

    private String bizKey(Long userId, int targetType, Long targetId) {
        return "like:" + targetType + ":" + targetId + ":" + userId;
    }

    private void requireValidTargetType(Integer targetType) {
        if (!UserLike.isValidTargetType(targetType)) {
            throw new BizException(ErrorCode.LIKE_TARGET_INVALID);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 自己定义的事件序列化失败属于编码错误（改了字段却没提供 getter 等），
            // 不是运行期状况，直接失败比写一条坏消息进本地消息表更好
            throw new IllegalStateException("点赞事件序列化失败", e);
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static RedisScript<Long> loadScript(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new DefaultRedisScript<>(StreamUtils.copyToString(in, StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 Lua 脚本失败：" + path, e);
        }
    }

    /** 目标的存在性与作者。用小 record 而不是直接返回实体，避免把实体泄露给调用链之外 */
    private record Target(Long ownerId) {
    }
}
