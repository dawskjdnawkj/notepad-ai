package com.notepad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 上传文件存储目录配置（图片保存到服务器磁盘）
 */
@Data
@Component
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

    /** 上传根目录（相对或绝对路径均可），默认为 ./uploads */
    private String dir = "./uploads";

    public Path getRootPath() {
        return resolve(dir);
    }

    /**
     * 解析上传目录。
     *
     * 相对路径按「后端模块」展开：从项目根目录启动（IDEA 常见）与从 backend 目录启动
     * （mvn 常见）最终都落到 backend/uploads，避免因启动方式不同而把图片写到两个地方
     * ——向量库文件用的是同一套规则，见 IndexDataDirectory。
     *
     * 绝对路径原样使用，生产环境用 NOTEPAD_UPLOAD_DIR 指到服务器上的目录即可。
     */
    private static Path resolve(String configuredPath) {
        Path configured = Paths.get(configuredPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        Path backendModule = workingDirectory.resolve("backend");
        if (Files.isRegularFile(backendModule.resolve("pom.xml"))) {
            return backendModule.resolve(configured).normalize();
        }
        return workingDirectory.resolve(configured).normalize();
    }
}
