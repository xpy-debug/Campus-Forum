package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞记录，对应 {@code t_user_like}。帖子与评论共用本表，靠 {@code targetType} 区分。
 *
 * <p><b>为什么没有 status 列：</b>取消点赞是物理 {@code DELETE}。
 * 唯一索引 {@code uk_user_target} 是点赞幂等的最终防线，
 * 而逻辑删除会让它失效——「取消后再点赞」会撞上残留的那一行。
 *
 * <p><b>为什么 {@code targetOwnerId} 要冗余：</b>「我收到的赞」是个高频时间线，
 * 冗余作者 ID 之后可以走 {@code idx_owner} 直接查，不必回表到帖子或评论表
 * 反查作者。代价是目标作者变更时要同步这一列——但点赞的目标作者本就不会变。
 */
@Data
@TableName("t_user_like")
public class UserLike implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TARGET_POST = 1;
    public static final int TARGET_COMMENT = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1帖子 2评论 */
    private Integer targetType;

    private Long targetId;

    /** 目标作者 ID，冗余以便直接查「我收到的赞」 */
    private Long targetOwnerId;

    private LocalDateTime createTime;

    public static boolean isValidTargetType(Integer targetType) {
        return targetType != null && (targetType == TARGET_POST || targetType == TARGET_COMMENT);
    }
}
