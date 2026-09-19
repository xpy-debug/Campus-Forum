package com.school.forum.forum.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 点赞 / 取消点赞请求。两者共用一个请求体，只是路径不同。
 */
@Data
public class LikeRequest {

    /** 1帖子 2评论。合法性在 Service 层校验（用 {@code UserLike.isValidTargetType}） */
    @NotNull(message = "目标类型不能为空")
    private Integer targetType;

    @NotNull(message = "目标 ID 不能为空")
    private Long targetId;
}
