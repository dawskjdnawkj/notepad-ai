package com.notepad.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageService {

    /**
     * 上传图片：保存到磁盘，插入 note_image（note_id 为空），返回访问 URL
     */
    String upload(Long userId, MultipartFile file);

    /**
     * 解析笔记正文 HTML 中的图片，并把该用户下的临时图片绑定到笔记
     */
    void bindImagesToNote(Long userId, Long noteId, String contentHtml);

    /**
     * 删除笔记时：删除该笔记下所有图片的磁盘文件，并逻辑删除 note_image 记录
     */
    void deleteImagesByNoteId(Long userId, Long noteId);

    /**
     * 清理孤儿图片（note_id 为空且创建时间超过 24 小时）：删除磁盘文件，物理删除记录
     */
    int cleanupOrphanImages();

    /**
     * 物理删除已逻辑删除且超过保留期的 note_image 记录（文件已随删除笔记时清理）
     */
    int purgeDeletedImages();
}
