package com.notepad.controller;

import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.service.ImageService;
import com.notepad.vo.ImageUploadVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @PostMapping("/upload")
    public Result<ImageUploadVO> upload(@RequestParam("file") MultipartFile file) {
        String url = imageService.upload(UserContext.getUserId(), file);
        return Result.ok(new ImageUploadVO(url));
    }
}
