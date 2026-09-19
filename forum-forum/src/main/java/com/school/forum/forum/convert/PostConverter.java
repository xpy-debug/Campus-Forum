package com.school.forum.forum.convert;

import com.school.forum.forum.entity.Post;
import com.school.forum.forum.entity.PostContent;
import com.school.forum.forum.vo.PostDetailVO;
import com.school.forum.forum.vo.PostVO;
import com.school.forum.forum.vo.TagVO;
import com.school.forum.user.api.UserBrief;

import java.util.List;

/**
 * 帖子实体到出参的转换。
 *
 * <p>方法签名里参数较多，是因为帖子的出参本身就是多源聚合的结果
 * （帖子 + 作者 + 标签 + 点赞态）。多出来的参数都在调用处显式写清楚，
 * 比在转换器里再注入几个服务去查要容易理解——转换器保持无依赖的静态方法，
 * 才能在任何上下文中被安全调用。
 */
public final class PostConverter {

    private PostConverter() {
    }

    public static PostVO toListVO(Post post,
                                  UserBrief author,
                                  List<TagVO> tags,
                                  long likeCount,
                                  boolean liked) {
        return new PostVO(
                post.getId(),
                post.getBoardId(),
                post.getTitle(),
                post.getSummary(),
                post.getCoverImage(),
                value(post.getType()),
                author,
                likeCount,
                value(post.getCommentCount()),
                value(post.getViewCount()),
                liked,
                tags,
                post.getPublishTime());
    }

    public static PostDetailVO toDetailVO(Post post,
                                          PostContent content,
                                          String boardName,
                                          UserBrief author,
                                          List<TagVO> tags,
                                          long likeCount,
                                          long viewCount,
                                          boolean liked) {
        return new PostDetailVO(
                post.getId(),
                post.getBoardId(),
                boardName,
                post.getTitle(),
                content == null ? "" : content.getContent(),
                content == null ? "" : content.getContentHtml(),
                author,
                likeCount,
                value(post.getCommentCount()),
                viewCount,
                content == null ? 0 : value(content.getWordCount()),
                liked,
                tags,
                post.getPublishTime());
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
