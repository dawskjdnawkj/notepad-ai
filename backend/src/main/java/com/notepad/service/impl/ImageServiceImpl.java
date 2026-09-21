package com.notepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notepad.common.BusinessException;
import com.notepad.config.UploadProperties;
import com.notepad.entity.NoteImage;
import com.notepad.mapper.NoteImageMapper;
import com.notepad.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {

    private static final Pattern IMG_SRC = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']");

    /** original_name 列宽 */
    private static final int ORIGINAL_NAME_MAX = 255;

    private final NoteImageMapper noteImageMapper;
    private final UploadProperties uploadProperties;

    @Override
    public String upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "图片不能为空");
        }
        String rawName = file.getOriginalFilename();
        String originalExt = extractExt(rawName);
        String originalName = safeOriginalName(rawName);
        String ext;
        try {
            ext = detectImageExtension(file);
        } catch (IOException e) {
            throw new BusinessException(400, "无法读取图片文件");
        }
        if (ext == null || !extensionMatches(originalExt, ext) || !contentTypeMatches(file.getContentType(), ext)) {
            throw new BusinessException(400, "仅支持上传真实的 JPG、PNG、GIF、WEBP 或 BMP 图片");
        }

        LocalDate now = LocalDate.now();
        String relativePath = "images/" + now.getYear() + "/" +
                String.format("%02d", now.getMonthValue()) + "/" +
                UUID.randomUUID().toString().replace("-", "") + ext;
        String url = "/uploads/" + relativePath;

        Path root = uploadProperties.getRootPath();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(400, "图片路径不合法");
        }
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException(500, "图片保存失败");
        }

        NoteImage noteImage = new NoteImage();
        noteImage.setUserId(userId);
        noteImage.setNoteId(null);
        noteImage.setUrl(url);
        noteImage.setFileName(relativePath);
        noteImage.setOriginalName(originalName);
        noteImage.setSize(file.getSize());
        noteImageMapper.insert(noteImage);
        return url;
    }

    @Override
    public void bindImagesToNote(Long userId, Long noteId, String contentHtml) {
        if (contentHtml == null || contentHtml.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>();
        Matcher matcher = IMG_SRC.matcher(contentHtml);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        if (urls.isEmpty()) {
            return;
        }
        noteImageMapper.bindToNote(userId, noteId, urls);
    }

    @Override
    public void deleteImagesByNoteId(Long userId, Long noteId) {
        List<NoteImage> images = noteImageMapper.selectList(new LambdaQueryWrapper<NoteImage>()
                .eq(NoteImage::getUserId, userId)
                .eq(NoteImage::getNoteId, noteId));
        for (NoteImage image : images) {
            deleteFileQuietly(image);
        }
        if (!images.isEmpty()) {
            // 逻辑删除（@TableLogic 自动置 deleted=1）
            noteImageMapper.deleteByIds(images.stream().map(NoteImage::getId).toList());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanupOrphanImages() {
        LocalDateTime before = LocalDateTime.now().minusHours(24);
        List<NoteImage> orphans = noteImageMapper.selectList(new LambdaQueryWrapper<NoteImage>()
                .isNull(NoteImage::getNoteId)
                .lt(NoteImage::getCreateTime, before));
        for (NoteImage image : orphans) {
            deleteFileQuietly(image);
        }
        if (orphans.isEmpty()) {
            return 0;
        }
        noteImageMapper.physicalDeleteByIds(orphans.stream().map(NoteImage::getId).toList());
        return orphans.size();
    }

    @Override
    public int purgeDeletedImages() {
        LocalDateTime before = LocalDateTime.now().minusDays(30);
        return noteImageMapper.purgeDeleted(before);
    }

    private void deleteFileQuietly(NoteImage image) {
        String fileName = image.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        try {
            Path root = uploadProperties.getRootPath();
            Path target = root.resolve(fileName).normalize();
            if (!target.startsWith(root)) {
                log.warn("拒绝删除上传目录外的文件: {}", fileName);
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // 文件删除失败不能打断主业务流程，只记录日志
            log.warn("删除图片文件失败: {}", fileName, e);
        }
    }

    /**
     * original_name 列宽 VARCHAR(255)，且只用于展示（落盘名是 UUID），
     * 所以这里去掉客户端可能带上的路径部分并截断。
     * 不截断的话，multipart 的 filename 超过 255 字符会让文件已经落盘、
     * 入库却报错，留下一个要等孤儿清理任务才回收的垃圾文件，并返回 500。
     */
    private static String safeOriginalName(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        return cleaned.length() > ORIGINAL_NAME_MAX ? cleaned.substring(0, ORIGINAL_NAME_MAX) : cleaned;
    }

    private String extractExt(String filename) {
        if (filename == null) {
            return "";
        }
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i).toLowerCase() : "";
    }

    private String detectImageExtension(MultipartFile file) throws IOException {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(12);
        }
        if (header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return ".jpg";
        }
        if (header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G' && header[4] == 0x0d
                && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a) {
            return ".png";
        }
        if (header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
            return ".gif";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                && header[3] == 'F' && header[8] == 'W' && header[9] == 'E'
                && header[10] == 'B' && header[11] == 'P') {
            return ".webp";
        }
        if (header.length >= 2 && header[0] == 'B' && header[1] == 'M') {
            return ".bmp";
        }
        return null;
    }

    private boolean extensionMatches(String originalExt, String detectedExt) {
        if (".jpg".equals(detectedExt)) {
            return ".jpg".equals(originalExt) || ".jpeg".equals(originalExt);
        }
        return detectedExt.equals(originalExt);
    }

    private boolean contentTypeMatches(String contentType, String detectedExt) {
        if (contentType == null) {
            return false;
        }
        return switch (detectedExt) {
            case ".jpg" -> "image/jpeg".equalsIgnoreCase(contentType);
            case ".png" -> "image/png".equalsIgnoreCase(contentType);
            case ".gif" -> "image/gif".equalsIgnoreCase(contentType);
            case ".webp" -> "image/webp".equalsIgnoreCase(contentType);
            case ".bmp" -> "image/bmp".equalsIgnoreCase(contentType)
                    || "image/x-ms-bmp".equalsIgnoreCase(contentType);
            default -> false;
        };
    }
}
