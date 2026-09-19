package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.PostContent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子正文数据访问。
 *
 * <p>按主键查正文即可，无需手写 SQL——{@code post_id} 是这张表的主键，
 * {@code selectById} 直接命中聚簇索引。
 */
@Mapper
public interface PostContentMapper extends BaseMapper<PostContent> {
}
