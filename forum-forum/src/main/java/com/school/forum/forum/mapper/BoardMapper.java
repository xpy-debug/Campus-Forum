package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.Board;
import org.apache.ibatis.annotations.Mapper;

/**
 * 板块数据访问。板块总共只有几十行，直接用 {@code BaseMapper} 的通用方法即可，
 * 无需手写 SQL，也不需要缓存——一次主键查询的成本远低于维护缓存的复杂度。
 */
@Mapper
public interface BoardMapper extends BaseMapper<Board> {
}
