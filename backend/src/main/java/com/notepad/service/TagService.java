package com.notepad.service;

import com.notepad.dto.TagCreateRequest;
import com.notepad.vo.TagVO;

import java.util.List;

public interface TagService {

    List<TagVO> list(Long userId);

    TagVO create(Long userId, TagCreateRequest request);

    void delete(Long userId, Long tagId);
}
