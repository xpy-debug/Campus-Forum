package com.school.forum.forum.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.forum.convert.CommentConverter;
import com.school.forum.forum.dto.CommentCreateRequest;
import com.school.forum.forum.entity.Comment;
import com.school.forum.forum.entity.Post;
import com.school.forum.forum.entity.UserLike;
import com.school.forum.forum.mapper.CommentMapper;
import com.school.forum.forum.mapper.PostMapper;
import com.school.forum.forum.service.CommentService;
import com.school.forum.forum.service.LikeService;
import com.school.forum.forum.service.PostService;
import com.school.forum.forum.support.CursorUtil;
import com.school.forum.forum.vo.CommentVO;
import com.school.forum.infrastructure.web.auth.LoginUser;
import com.school.forum.user.api.UserApi;
import com.school.forum.user.api.UserBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 评论实现。
 *
 * <p>与点赞链路的取舍不同：<b>评论同步落库，不经过 MQ</b>。
 * 评论的写入频率比点赞低两三个数量级，而用户对「评论发出去了没有」的
 * 确定性要求更高——点赞多一个少一个无所谓，评论消失了就是内容丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 一级评论下默认预览的回复条数。再多会让一页评论变得很长，其余由「查看全部回复」翻 */
    private static final int REPLY_PREVIEW = 2;

    /**
     * 楼层发号器的空闲寿命。
     * <p>过期后下次评论会重新用 {@code MAX(floor)} 播种，因此不存在「过期后楼层回退」的问题。
     */
    private static final Duration FLOOR_TTL = Duration.ofDays(30);

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final PostService postService;
    private final LikeService likeService;
    private final UserApi userApi;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // ==================== 发表 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentVO create(Long userId, CommentCreateRequest request) {
        Post post = postService.requirePublished(request.getPostId());
        LocalDateTime now = LocalDateTime.now();

        Comment comment = new Comment();
        comment.setPostId(post.getId());
        comment.setUserId(userId);
        comment.setContent(request.getContent());
        comment.setImages(toJson(request.getImages()));
        comment.setStatus(Comment.STATUS_NORMAL);
        comment.setIsAuthor(Objects.equals(post.getUserId(), userId) ? 1 : 0);

        Long parentId = request.getParentId();
        if (parentId == null || parentId == Comment.NO_PARENT) {
            comment.setLevel(Comment.LEVEL_ROOT);
            comment.setRootId(Comment.NO_PARENT);
            comment.setParentId(Comment.NO_PARENT);
            comment.setReplyUserId(Comment.NO_PARENT);
            comment.setFloor(nextFloor(post.getId()));
        } else {
            Comment parent = requireVisible(parentId);
            if (!Objects.equals(parent.getPostId(), post.getId())) {
                throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
            }
            comment.setLevel(Comment.LEVEL_REPLY);
            // 回复二级评论时，root 要取父评论的 root 而不是父评论本身。
            // 否则「查某条一级评论下的全部回复」会漏掉这一条，
            // 用户看到自己的回复「消失」了
            comment.setRootId(isRoot(parent) ? parent.getId() : parent.getRootId());
            comment.setParentId(parent.getId());
            comment.setReplyUserId(parent.getUserId());
            comment.setFloor(0);
        }
        commentMapper.insert(comment);

        // 评论数与最后评论时间在同一条 UPDATE 里写完：拆成两条的话，
        // 「最新回复」排序会在极短的窗口里看到新评论数配着旧时间
        postMapper.addCommentCount(post.getId(), 1, now);
        if (!isRoot(comment)) {
            commentMapper.addReplyCount(comment.getRootId(), 1);
        }

        // 回填作者信息再返回，前端就能把这条评论直接插进列表，
        // 不必为了拿昵称和头像再拉一次评论列表
        Map<Long, UserBrief> authors = userApi.batchGetBrief(userIdsOf(List.of(comment)));
        return toVO(comment, authors, Map.of(), Set.of(), List.of());
    }

    /**
     * 取下一个楼层号。
     *
     * <p><b>不能用 {@code COUNT(*) + 1}：</b>并发下两个请求会读到同一个计数，
     * 于是出现两个「3 楼」——而楼层是用来引用评论的（「详见 3 楼」），
     * 重复楼层会让人无法定位。{@code INCR} 是原子的，天然无竞争。
     */
    private int nextFloor(Long postId) {
        String key = RedisKey.commentFloor(postId);
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            // 冷启动（Redis 重启，或该帖第一次收到评论）时用库里的最大楼层播种。
            // SETNX 而不是 SET：并发下只允许一个请求写入基准值，其余请求的写入被忽略，
            // 随后各自 INCR 依然拿到互不相同的楼层
            Integer maxFloor = commentMapper.selectMaxFloor(postId);
            redis.opsForValue().setIfAbsent(key,
                    String.valueOf(maxFloor == null ? 0 : maxFloor), FLOOR_TTL);
        }
        Long floor = redis.opsForValue().increment(key);
        return floor == null ? 1 : floor.intValue();
    }

    // ==================== 列表 ====================

    @Override
    public PageResult<CommentVO> list(Long postId, String cursor, int size, Long currentUserId) {
        int limit = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        PageResult<Comment> roots = PageResult.ofCursor(
                commentMapper.selectRootPage(postId, parseFloor(cursor), limit + 1),
                limit,
                comment -> CursorUtil.encode(String.valueOf(floorOf(comment)), comment.getId()));

        List<Comment> rootList = roots.getList();
        List<Long> rootIds = rootList.stream().map(Comment::getId).toList();
        Map<Long, List<Comment>> repliesByRoot = rootIds.isEmpty()
                ? Map.of()
                : commentMapper.selectTopReplies(rootIds, REPLY_PREVIEW).stream()
                .collect(Collectors.groupingBy(Comment::getRootId));

        List<Comment> all = new ArrayList<>(rootList);
        repliesByRoot.values().forEach(all::addAll);

        Map<Long, UserBrief> authors = userApi.batchGetBrief(userIdsOf(all));
        Set<Long> liked = likeService.likedTargets(currentUserId, UserLike.TARGET_COMMENT,
                all.stream().map(Comment::getId).toList());
        Map<Long, Long> likeCounts = likeService.mergeLikeCounts(UserLike.TARGET_COMMENT, dbCountsOf(all));

        PageResult<CommentVO> result = roots.map(root -> toVO(root, authors, likeCounts, liked,
                repliesByRoot.getOrDefault(root.getId(), List.of()).stream()
                        .map(reply -> toVO(reply, authors, likeCounts, liked, List.of()))
                        .toList()));
        return result;
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long commentId, Long operatorId, int operatorRole) {
        Comment comment = requireVisible(commentId);
        Post post = postMapper.selectById(comment.getPostId());

        boolean self = Objects.equals(comment.getUserId(), operatorId);
        boolean postOwner = post != null && Objects.equals(post.getUserId(), operatorId);
        boolean moderator = operatorRole >= LoginUser.ROLE_MODERATOR;
        if (!self && !postOwner && !moderator) {
            throw new BizException(ErrorCode.COMMENT_NO_PERMISSION);
        }

        // 只删这一条，不动它的子回复：父评论被删后子回复仍能读，
        // 渲染成「该评论已删除」的占位，对话上下文才不至于断裂
        commentMapper.softDelete(commentId);
        postMapper.addCommentCount(comment.getPostId(), -1, null);
        if (!isRoot(comment)) {
            commentMapper.addReplyCount(comment.getRootId(), -1);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 组装出参。
     *
     * @param authors    批量查好的作者，缺失时出参里的 user 为 null，
     *                   前端按「已注销用户」渲染——比在服务层造一个假用户干净
     * @param likeCounts 实时点赞数
     * @param liked      当前用户点赞过的评论 ID
     */
    private CommentVO toVO(Comment comment,
                           Map<Long, UserBrief> authors,
                           Map<Long, Long> likeCounts,
                           Set<Long> liked,
                           List<CommentVO> replies) {
        return CommentConverter.toVO(
                comment,
                authors.get(comment.getUserId()),
                // 一级评论的 reply_user_id 是 0，取不到自然为 null
                authors.get(comment.getReplyUserId()),
                liked.contains(comment.getId()),
                likeCounts.getOrDefault(comment.getId(), count(comment.getLikeCount())),
                replies);
    }

    private static Set<Long> userIdsOf(Collection<Comment> comments) {
        return comments.stream()
                .flatMap(comment -> Stream.of(comment.getUserId(), comment.getReplyUserId()))
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
    }

    private static Map<Long, Long> dbCountsOf(Collection<Comment> comments) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Comment comment : comments) {
            counts.put(comment.getId(), count(comment.getLikeCount()));
        }
        return counts;
    }

    private Comment requireVisible(Long commentId) {
        Comment comment = commentId == null ? null : commentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() == null
                || comment.getStatus() == Comment.STATUS_DELETED) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        return comment;
    }

    private static boolean isRoot(Comment comment) {
        return comment.getLevel() != null && comment.getLevel() == Comment.LEVEL_ROOT;
    }

    private static int floorOf(Comment comment) {
        return comment.getFloor() == null ? 0 : comment.getFloor();
    }

    /** 游标里只有楼层一个值。解不出来就当作第一页——游标是客户端传来的不可信数据 */
    private static Integer parseFloor(String cursor) {
        String raw = CursorUtil.decodeSortKey(cursor);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("评论图片列表序列化失败", e);
        }
    }

    private static long count(Integer value) {
        return value == null ? 0L : value;
    }
}
