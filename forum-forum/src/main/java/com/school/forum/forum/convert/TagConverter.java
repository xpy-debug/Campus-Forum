package com.school.forum.forum.convert;

import com.school.forum.forum.entity.Tag;
import com.school.forum.forum.vo.TagVO;

import java.util.List;

/**
 * 标签实体到出参的转换。
 */
public final class TagConverter {

    private TagConverter() {
    }

    public static TagVO toVO(Tag tag) {
        return new TagVO(tag.getId(), tag.getName());
    }

    public static List<TagVO> toVOList(List<Tag> tags) {
        return tags.stream().map(TagConverter::toVO).toList();
    }
}
