package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    private final ConcurrentMap<Path, FileLockEntry> fileLocks = new ConcurrentHashMap<>();

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            Path filePath = uploadDir.resolve(originalFilename).normalize();
            if (!filePath.toAbsolutePath().startsWith(uploadDir.toAbsolutePath())) {
                return ResponseEntity.badRequest().body("文件名不合法");
            }

            Path lockKey = filePath.toAbsolutePath().normalize();
            FileLockEntry lockEntry = fileLocks.compute(lockKey, (ignored, existing) -> {
                FileLockEntry entry = existing == null ? new FileLockEntry() : existing;
                entry.users++;
                return entry;
            });
            lockEntry.lock.lock();
            try {
                return replaceFileAndIndex(file, originalFilename, uploadDir, filePath);
            } finally {
                lockEntry.lock.unlock();
                fileLocks.computeIfPresent(lockKey, (ignored, existing) -> {
                    existing.users--;
                    return existing.users == 0 ? null : existing;
                });
            }

        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    private ResponseEntity<?> replaceFileAndIndex(
            MultipartFile file,
            String originalFilename,
            Path uploadDir,
            Path filePath
    ) throws IOException {
        Path temporaryFilePath = Files.createTempFile(uploadDir, "upload-", ".tmp");
        Path backupFilePath = null;
        boolean newFileCommitted = false;
        boolean oldFileBackedUp = false;
        try {
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, temporaryFilePath, StandardCopyOption.REPLACE_EXISTING);
            }

            if (Files.isRegularFile(filePath)) {
                backupFilePath = Files.createTempFile(uploadDir, "backup-", ".bak");
                Files.move(filePath, backupFilePath, StandardCopyOption.REPLACE_EXISTING);
                oldFileBackedUp = true;
            }

            Files.move(temporaryFilePath, filePath, StandardCopyOption.REPLACE_EXISTING);
            newFileCommitted = true;

            logger.info("开始为上传文件创建向量索引: {}", filePath);
            vectorIndexService.indexSingleFile(
                    filePath.toString(),
                    filePath.toString(),
                    backupFilePath == null ? null : backupFilePath.toString()
            );

            deleteBackupQuietly(backupFilePath);
            logger.info("文件上传与向量索引创建成功: {}", filePath);
        } catch (Exception e) {
            rollbackFileReplacement(
                    filePath,
                    backupFilePath,
                    newFileCommitted,
                    oldFileBackedUp,
                    e
            );
            deleteTemporaryFileQuietly(temporaryFilePath);

            logger.error("知识库入库失败: {}, 错误: {}", filePath, e.getMessage(), e);
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("知识库入库失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }

        FileUploadRes response = new FileUploadRes(
                originalFilename,
                filePath.toString(),
                file.getSize()
        );

        ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
        apiResponse.setCode(200);
        apiResponse.setMessage("success");
        apiResponse.setData(response);

        return ResponseEntity.ok(apiResponse);
    }

    private void rollbackFileReplacement(
            Path filePath,
            Path backupFilePath,
            boolean newFileCommitted,
            boolean oldFileBackedUp,
            Exception originalException
    ) {
        try {
            if (newFileCommitted) {
                Files.deleteIfExists(filePath);
            }
            if (oldFileBackedUp && backupFilePath != null && Files.exists(backupFilePath)) {
                Files.move(backupFilePath, filePath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                deleteBackupQuietly(backupFilePath);
            }
        } catch (IOException rollbackException) {
            originalException.addSuppressed(rollbackException);
            logger.error("恢复旧文件失败，备份保留在: {}", backupFilePath, rollbackException);
        }
    }

    private void deleteTemporaryFileQuietly(Path temporaryFilePath) {
        try {
            Files.deleteIfExists(temporaryFilePath);
        } catch (IOException cleanupException) {
            logger.warn("清理临时上传文件失败: {}", temporaryFilePath, cleanupException);
        }
    }

    private void deleteBackupQuietly(Path backupFilePath) {
        if (backupFilePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(backupFilePath);
        } catch (IOException cleanupException) {
            logger.warn("清理旧文件备份失败，备份保留在: {}", backupFilePath, cleanupException);
        }
    }

    private static final class FileLockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    /**
     * 统一 API 响应格式
     */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
