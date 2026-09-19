package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.Tag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标签数据访问。
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {
}
