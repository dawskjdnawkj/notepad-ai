package com.notepad.ai.service.vector;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 索引数据目录的解析规则。两种存储共用同一套规则，
 * 保证切换存储后备份目录仍在同一处、已有备份不会"消失"。
 */
public final class IndexDataDirectory {

    private IndexDataDirectory() {
    }

    /**
     * 解析配置里的数据文件路径（绝对路径直接用；相对路径按工作目录展开）。
     *
     * IntelliJ 可能以项目根目录作为工作目录；此时仍应使用 backend/data，
     * 避免因启动方式不同而创建第二份数据目录。
     */
    public static Path resolve(String configuredPath) {
        Path configured = Path.of(configuredPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path backendModule = workingDirectory.resolve("backend");
        if (Files.isRegularFile(backendModule.resolve("pom.xml"))) {
            return backendModule.resolve(configured).normalize();
        }
        return workingDirectory.resolve(configured).normalize();
    }
}
