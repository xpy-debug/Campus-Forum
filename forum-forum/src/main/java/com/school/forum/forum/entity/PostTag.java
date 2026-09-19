package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子-标签关联，对应 {@code t_post_tag}。
 *
 * <p>删除关联走物理删除，理由同 {@code t_user_like}：唯一索引
 * {@code uk_post_tag} 是防重复关联的最后防线，而带 {@code status} 的逻辑删除
 * 会让「删了再加回来」撞上唯一索引。
 */
@Data
@TableName("t_post_tag")
public class PostTag implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long tagId;

    private LocalDateTime createTime;
}
