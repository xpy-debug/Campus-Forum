package com.school.forum.forum.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.forum.convert.PostConverter;
import com.school.forum.forum.convert.TagConverter;
import com.school.forum.forum.dto.PostCreateRequest;
import com.school.forum.forum.entity.Board;
import com.school.forum.forum.entity.Post;
import com.school.forum.forum.entity.PostContent;
import com.school.forum.forum.entity.PostTag;
import com.school.forum.forum.entity.Tag;
import com.school.forum.forum.entity.UserLike;
import com.school.forum.forum.mapper.BoardMapper;
import com.school.forum.forum.mapper.PostContentMapper;
import com.school.forum.forum.mapper.PostMapper;
import com.school.forum.forum.mapper.PostTagMapper;
import com.school.forum.forum.mapper.TagMapper;
import com.school.forum.forum.service.BoardService;
import com.school.forum.forum.service.LikeService;
import com.school.forum.forum.service.PostService;
import com.school.forum.forum.support.ContentExtractor;
import com.school.forum.forum.support.CursorUtil;
import com.school.forum.forum.support.PostSort;
import com.school.forum.forum.vo.PostDetailVO;
import com.school.forum.forum.vo.PostVO;
import com.school.forum.forum.vo.TagVO;
import com.school.forum.user.api.UserApi;
import com.school.forum.user.api.UserBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子实现。
 *
 * <p>三个方法各自的关注点：<b>发帖</b>要保证多张表写入的原子性与重复提交的幂等；
 * <b>列表</b>要把「一页帖子」所需的四类数据（作者、标签、点赞数、点赞态）
 * 都压成常数次查询；<b>详情</b>要顺带记一次浏览而不写库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    /** 每页上限。不设上限的话一个 size=100000 的请求就能把整张表拖进内存 */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 幂等令牌有效期。取值权衡：短了挡不住用户第二天重试，长了白占内存 */
    private static final Duration IDEMPOTENT_TTL = Duration.ofHours(24);

    /** 同一用户重复浏览同一帖子的去重窗口 */
    private static final Duration VIEW_DEDUP_TTL = Duration.ofMinutes(30);

    /** 浏览计数键的空闲寿命，由回写任务每次刷新 */
    private static final Duration VIEW_COUNTER_TTL = Duration.ofDays(7);

    private final PostMapper postMapper;
    private final PostContentMapper postContentMapper;
    private final PostTagMapper postTagMapper;
    private final TagMapper tagMapper;
    private final BoardMapper boardMapper;
    private final BoardService boardService;
    private final LikeService likeService;
    private final UserApi userApi;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // ==================== 发帖 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, String idempotencyKey, PostCreateRequest request) {
        Long existed = findByIdempotentKey(idempotencyKey);
        if (existed != null) {
            return existed;
        }

        Board board = boardService.requirePostable(request.getBoardId());
        boolean draft = request.isDraft();

        Post post = new Post();
        post.setBoardId(board.getId());
        post.setUserId(userId);
        post.setTitle(request.getTitle().trim());
        post.setSummary(ContentExtractor.summary(request.getContent()));
        post.setCoverImage(ContentExtractor.coverImage(request.getContent()));
        post.setType(Post.TYPE_NORMAL);
        post.setStatus(statusOf(board, draft));
        if (post.getStatus() == Post.STATUS_PUBLISHED) {
            // 发布时间是列表的排序键，只在真正发布时写入。草稿与审核中的帖子留空，
            // 审核通过时再补，免得一条没通过的帖子先占住时间线上的位置
            post.setPublishTime(LocalDateTime.now());
        }
        // 热度分显式写 0 而不是留 null：(hot_score, id) 是行值比较，
        // 与 NULL 比较的结果恒为 NULL，会让这条帖子在按热度翻页时彻底消失。
        // 含时间衰减的热度重算属于定时任务的职责，本期不实现，
        // 因此 hot 排序当前等价于「按 id 倒序」
        post.setHotScore(BigDecimal.ZERO);
        postMapper.insert(post);

        saveContent(post.getId(), request);
        saveTags(post.getId(), request.getTags());
        if (post.getStatus() == Post.STATUS_PUBLISHED) {
            // 发帖数属于用户域的数据，必须走 UserApi。
            // 论坛域直接 UPDATE t_user 会把用户表的结构锁死在两个模块里
            userApi.addPostCount(userId, 1);
        }

        cacheIdempotentKey(idempotencyKey, post.getId());
        return post.getId();
    }

    private void saveContent(Long postId, PostCreateRequest request) {
        PostContent content = new PostContent();
        content.setPostId(postId);
        content.setContent(request.getContent());
        content.setImages(toJson(imagesOf(request)));
        content.setWordCount(ContentExtractor.wordCount(request.getContent()));
        // content_html 留空：Markdown 由前端渲染，服务端不为此引入一个解析器。
        // 将来若要在服务端预渲染，只需在这里补一行赋值，表结构不用动
        postContentMapper.insert(content);
    }

    private void saveTags(Long postId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<Long> distinct = tagIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return;
        }
        // 只接受存在且启用的标签。不存在的标签静默丢弃而不报错：
        // 标签是辅助信息，为了一个失效的标签让整篇帖子发不出去不合理
        List<Tag> valid = tagMapper.selectList(Wrappers.<Tag>lambdaQuery()
                .in(Tag::getId, distinct)
                .eq(Tag::getStatus, Tag.STATUS_NORMAL));
        for (Tag tag : valid) {
            PostTag relation = new PostTag();
            relation.setPostId(postId);
            relation.setTagId(tag.getId());
            postTagMapper.insert(relation);
        }
    }

    // ==================== 列表 ====================

    @Override
    public PageResult<PostVO> list(Long boardId, String sort, String cursor, int size, Long currentUserId) {
        int limit = normalizeSize(size);
        PostSort postSort = PostSort.of(sort);
        // 置顶帖只在第一页拼接：它在语义上「永远排在最前」，
        // 若每页都拼一次，翻到第三页还会再看到同样的几条
        boolean firstPage = isBlank(cursor);

        PageResult<Post> page = PageResult.ofCursor(
                queryPage(boardId, postSort, cursor, limit + 1),
                limit,
                post -> cursorOf(postSort, post));

        List<Post> ordered = new ArrayList<>();
        if (firstPage) {
            ordered.addAll(postMapper.selectPinned(boardId));
        }
        ordered.addAll(page.getList());

        PageResult<PostVO> result = new PageResult<>();
        result.setList(assemble(ordered, currentUserId));
        result.setNextCursor(page.getNextCursor());
        result.setHasMore(page.isHasMore());
        return result;
    }

    /** 多查一条用于判断是否还有下一页，因此 {@code size} 传的是 limit + 1 */
    private List<Post> queryPage(Long boardId, PostSort sort, String cursor, int size) {
        Long cursorId = CursorUtil.decodeId(cursor);
        if (sort == PostSort.HOT) {
            BigDecimal score = cursorId == null ? null : parseScore(CursorUtil.decodeSortKey(cursor));
            return postMapper.selectPageByHot(boardId, score, cursorId, size);
        }
        LocalDateTime time = cursorId == null ? null : CursorUtil.decodeTime(cursor);
        return postMapper.selectPageByTime(boardId, Post.TYPE_NORMAL, time, cursorId, size);
    }

    private String cursorOf(PostSort sort, Post post) {
        if (sort == PostSort.HOT) {
            BigDecimal score = post.getHotScore() == null ? BigDecimal.ZERO : post.getHotScore();
            // 热度分是小数，用字符串原样编码，避免 double 转换引入的精度误差
            return CursorUtil.encode(score.toPlainString(), post.getId());
        }
        return CursorUtil.encode(post.getPublishTime(), post.getId());
    }

    /**
     * 把实体列表补全成出参列表。
     *
     * <p><b>四类外部数据各查一次，总共五次查询，与页大小无关。</b>
     * 逐条去查作者、标签、点赞数会退化成 1 + 3N 次查询——20 条帖子就是 61 次往返，
     * 这是列表接口最容易踩的性能坑。本方法的存在就是为了让这个数字恒为 5。
     */
    private List<PostVO> assemble(List<Post> posts, Long currentUserId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = posts.stream().map(Post::getId).toList();
        Map<Long, UserBrief> authors = userApi.batchGetBrief(
                posts.stream().map(Post::getUserId).collect(Collectors.toSet()));
        Map<Long, List<TagVO>> tags = tagsOf(postIds);

        Map<Long, Long> dbCounts = new LinkedHashMap<>();
        for (Post post : posts) {
            dbCounts.put(post.getId(), count(post.getLikeCount()));
        }
        Map<Long, Long> likeCounts = likeService.mergeLikeCounts(UserLike.TARGET_POST, dbCounts);
        Set<Long> liked = likeService.likedTargets(currentUserId, UserLike.TARGET_POST, postIds);

        return posts.stream().map(post -> PostConverter.toListVO(
                post,
                authors.get(post.getUserId()),
                tags.getOrDefault(post.getId(), List.of()),
                likeCounts.getOrDefault(post.getId(), count(post.getLikeCount())),
                liked.contains(post.getId()))).toList();
    }

    /** 批量取帖子的标签。两次查询覆盖整页：先查关联，再查标签名 */
    private Map<Long, List<TagVO>> tagsOf(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        List<PostTag> relations = postTagMapper.selectList(
                Wrappers.<PostTag>lambdaQuery().in(PostTag::getPostId, postIds));
        if (relations.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = relations.stream().map(PostTag::getTagId).collect(Collectors.toSet());
        Map<Long, TagVO> tagsById = tagMapper.selectList(
                        Wrappers.<Tag>lambdaQuery().in(Tag::getId, tagIds))
                .stream().collect(Collectors.toMap(Tag::getId, TagConverter::toVO));

        Map<Long, List<TagVO>> result = new HashMap<>();
        for (PostTag relation : relations) {
            TagVO tag = tagsById.get(relation.getTagId());
            // 标签在关联之后被删除时直接跳过，而不是补一个空占位
            if (tag != null) {
                result.computeIfAbsent(relation.getPostId(), key -> new ArrayList<>()).add(tag);
            }
        }
        return result;
    }

    // ==================== 详情 ====================

    @Override
    public PostDetailVO detail(Long postId, Long currentUserId) {
        Post post = requirePublished(postId);
        PostContent content = postContentMapper.selectById(postId);
        Board board = boardMapper.selectById(post.getBoardId());
        long dbCount = count(post.getLikeCount());
        Map<Long, Long> likeCounts = likeService.mergeLikeCounts(UserLike.TARGET_POST, Map.of(postId, dbCount));

        return PostConverter.toDetailVO(
                post,
                content,
                board == null ? "" : board.getName(),
                userApi.getBrief(post.getUserId()),
                tagsOf(List.of(postId)).getOrDefault(postId, List.of()),
                likeCounts.getOrDefault(postId, dbCount),
                recordView(post, currentUserId),
                likeService.likedTargets(currentUserId, UserLike.TARGET_POST, List.of(postId)).contains(postId));
    }

    @Override
    public Post requirePublished(Long postId) {
        Post post = postId == null ? null : postMapper.selectById(postId);
        if (post == null || post.getStatus() == null || post.getStatus() != Post.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /**
     * 记一次浏览，返回「数据库值 + Redis 增量」的实时浏览数。
     *
     * <p><b>详情页不做 {@code UPDATE t_post SET view_count = view_count + 1}：</b>
     * 那会让每次浏览都变成一次写操作并持有行锁，热门帖子上就是排队。
     * 改为在 Redis 自增，由 {@code ViewCountSyncTask} 批量回写。
     *
     * <p>返回的是两者之和而不是 Redis 的值：Redis 里只有「上次回写之后的新增」，
     * 直接返回它会让浏览量在回写发生的瞬间从 1000 掉回 20。
     */
    private long recordView(Post post, Long userId) {
        String viewKey = RedisKey.postView(post.getId());
        long base = count(post.getViewCount());

        boolean shouldCount = userId == null
                || Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(RedisKey.postViewed(post.getId(), userId), "1", VIEW_DEDUP_TTL));
        if (!shouldCount) {
            // 去重命中：增量已经记过了，直接读回来返回
            return base + Math.max(0L, parseLongOrZero(redis.opsForValue().get(viewKey)));
        }

        Long delta = redis.opsForValue().increment(viewKey);
        // 把帖子记进待回写集合，回写任务才知道该处理谁。
        // 用集合而不是 KEYS 扫描：KEYS 是 O(N) 且会阻塞整个 Redis 实例
        redis.opsForSet().add(RedisKey.postViewDirty(), String.valueOf(post.getId()));
        if (delta != null && delta == 1L) {
            // 只在计数键刚被创建时设过期时间，避免每次浏览都刷新它——
            // 那会让一个无人问津的帖子的 key 一直留在内存里
            redis.expire(viewKey, VIEW_COUNTER_TTL);
        }
        return base + Math.max(0L, delta == null ? 0L : delta);
    }

    // ==================== 内部工具 ====================

    private int statusOf(Board board, boolean draft) {
        if (draft) {
            return Post.STATUS_DRAFT;
        }
        return board.getPostAudit() != null && board.getPostAudit() == 1
                ? Post.STATUS_AUDITING
                : Post.STATUS_PUBLISHED;
    }

    /**
     * 合并请求里显式上传的图片与正文中出现的图片，保持出现顺序并去重。
     * 两者都存进 {@code t_post_content.images}：封面图取它的第一项。
     */
    private List<String> imagesOf(PostCreateRequest request) {
        Set<String> images = new LinkedHashSet<>();
        if (request.getImages() != null) {
            request.getImages().stream().filter(Objects::nonNull).forEach(images::add);
        }
        images.addAll(ContentExtractor.imageUrls(request.getContent()));
        return List.copyOf(images);
    }

    private Long findByIdempotentKey(String key) {
        if (isBlank(key)) {
            return null;
        }
        String value = redis.opsForValue().get(RedisKey.postIdempotent(key));
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("幂等令牌的值不是合法的帖子 ID，按未提交处理。value={}", value);
            return null;
        }
    }

    /**
     * 记录幂等令牌。
     *
     * <p><b>必须在事务提交之后写。</b>若在事务内写而事务随后回滚，这个 key 会在
     * 24 小时内一直指向一个并不存在的帖子：用户重试时拿到一个假 ID，
     * 而且因为 key 存在，再怎么重试也纠正不过来。
     *
     * <p>局限性：它挡的是「提交完成后客户端没收到响应而重试」这一类重复提交，
     * 挡不住两个请求同时到达（那时都还没提交，都查不到 key）。要挡住后者需要
     * 加锁，而发帖本身低频，不值得为它引入一次分布式锁往返。
     */
    private void cacheIdempotentKey(String key, Long postId) {
        if (isBlank(key)) {
            return;
        }
        String redisKey = RedisKey.postIdempotent(key);
        String value = String.valueOf(postId);
        Runnable action = () -> redis.opsForValue().set(redisKey, value, IDEMPOTENT_TTL);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String toJson(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            // 自己组装的数据序列化失败属于编码错误，不是运行期状况
            throw new IllegalStateException("正文图片列表序列化失败", e);
        }
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static long count(Integer value) {
        return value == null ? 0L : value;
    }

    private static long parseLongOrZero(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal parseScore(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
