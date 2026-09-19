package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.PostTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子-标签关联数据访问。
 */
@Mapper
public interface PostTagMapper extends BaseMapper<PostTag> {
}
