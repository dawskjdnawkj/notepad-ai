package com.notepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notepad.common.BusinessException;
import com.notepad.mapper.NoteTagMapper;
import com.notepad.dto.TagCreateRequest;
import com.notepad.entity.Tag;
import com.notepad.mapper.TagMapper;
import com.notepad.service.TagService;
import com.notepad.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;
    private final NoteTagMapper noteTagMapper;

    @Override
    public List<TagVO> list(Long userId) {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getUserId, userId)
                        .orderByDesc(Tag::getCreateTime))
                .stream()
                .map(tag -> new TagVO(tag.getId(), tag.getName(),
                        noteTagMapper.countByTagId(tag.getId()), tag.getCreateTime()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TagVO create(Long userId, TagCreateRequest request) {
        String name = request.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "标签名称不能为空");
        }
        Long count = tagMapper.selectCount(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getUserId, userId)
                .eq(Tag::getName, name));
        if (count != null && count > 0) {
            throw new BusinessException(409, "标签名称已存在");
        }
        Tag tag = new Tag();
        tag.setUserId(userId);
        tag.setName(name);
        tagMapper.insert(tag);
        return new TagVO(tag.getId(), name, 0L, tag.getCreateTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long tagId) {
        Tag tag = tagMapper.selectById(tagId);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(404, "标签不存在");
        }
        noteTagMapper.deleteByTagId(tagId);
        tagMapper.deleteById(tagId);
    }
}
