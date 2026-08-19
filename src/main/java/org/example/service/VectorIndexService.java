package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        indexSingleFile(filePath, filePath, null);
    }

    /**
     * 读取指定文件的内容，并以 logicalSourcePath 作为知识库中该资料的正式来源路径。
     */
    public void indexSingleFile(String filePath, String logicalSourcePath) throws Exception {
        Path contentPath = Paths.get(filePath).normalize();
        Path sourcePath = Paths.get(logicalSourcePath).normalize();
        String rollbackFilePath = null;
        if (!contentPath.toAbsolutePath().equals(sourcePath.toAbsolutePath())
                && Files.isRegularFile(sourcePath)) {
            rollbackFilePath = sourcePath.toString();
        }
        indexSingleFile(filePath, logicalSourcePath, rollbackFilePath);
    }

    /**
     * 索引正式文件；rollbackFilePath 指向被替换前的旧文件，用于写入失败时恢复旧向量。
     */
    public void indexSingleFile(
            String filePath,
            String logicalSourcePath,
            String rollbackFilePath
    ) throws Exception {
        Path path = Paths.get(filePath).normalize();
        Path sourcePath = Paths.get(logicalSourcePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        // 2. 先准备新资料的全部向量；此阶段不触碰 Milvus。
        List<PreparedChunk> newChunks = prepareChunks(content, sourcePath.toString());

        // 3. 同名资料更新时，也提前准备旧资料的向量备份，用于后续补偿。
        List<PreparedChunk> oldChunks = Collections.emptyList();
        if (rollbackFilePath != null) {
            Path rollbackPath = Paths.get(rollbackFilePath).normalize();
            if (Files.isRegularFile(rollbackPath)) {
                oldChunks = prepareChunks(Files.readString(rollbackPath), sourcePath.toString());
            }
        }

        // 4. 两份向量都准备好后，才替换 Milvus 中的正式资料。
        deleteExistingData(sourcePath.toString());
        try {
            insertPreparedChunks(newChunks);
        } catch (Exception indexingException) {
            logger.error("新资料索引失败，开始恢复旧资料: {}", sourcePath, indexingException);
            restoreOldVectors(sourcePath.toString(), oldChunks, indexingException);
            throw indexingException;
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, newChunks.size());
    }

    private List<PreparedChunk> prepareChunks(String content, String logicalSourcePath) {
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, logicalSourcePath);
        logger.info("文档分片完成: {} -> {} 个分片", logicalSourcePath, chunks.size());

        List<PreparedChunk> preparedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            try {
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
                Map<String, Object> metadata = buildMetadata(logicalSourcePath, chunk, chunks.size());
                preparedChunks.add(new PreparedChunk(chunk.getContent(), vector, metadata, chunk.getChunkIndex()));
            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 向量准备失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片向量准备失败: " + e.getMessage(), e);
            }
        }
        return preparedChunks;
    }

    private void insertPreparedChunks(List<PreparedChunk> preparedChunks) {
        for (int i = 0; i < preparedChunks.size(); i++) {
            PreparedChunk preparedChunk = preparedChunks.get(i);
            try {
                insertToMilvus(
                        preparedChunk.content(),
                        preparedChunk.vector(),
                        preparedChunk.metadata(),
                        preparedChunk.chunkIndex()
                );
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, preparedChunks.size());
            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, preparedChunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }
    }

    private void restoreOldVectors(String logicalSourcePath, List<PreparedChunk> oldChunks, Exception indexingException) {
        try {
            deleteExistingData(logicalSourcePath);
        } catch (Exception cleanupException) {
            indexingException.addSuppressed(cleanupException);
            logger.error("清理部分新向量失败: {}", logicalSourcePath, cleanupException);
            return;
        }

        if (oldChunks.isEmpty()) {
            return;
        }

        try {
            insertPreparedChunks(oldChunks);
            logger.info("旧资料向量恢复成功: {}", logicalSourcePath);
        } catch (Exception restoreException) {
            indexingException.addSuppressed(restoreException);
            logger.error("旧资料向量恢复失败: {}", logicalSourcePath, restoreException);
            try {
                deleteExistingData(logicalSourcePath);
            } catch (Exception finalCleanupException) {
                indexingException.addSuppressed(finalCleanupException);
                logger.error("清理部分恢复的旧向量失败: {}", logicalSourcePath, finalCleanupException);
            }
        }
    }

    /**
     * 删除文件的旧数据（根据 metadata._source）
     */
    private void deleteExistingData(String filePath) throws Exception {
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");
            
            // 构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);
            
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            // 确保 collection 已加载（删除操作需要集合已加载）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            // 状态码 65535 表示集合已经加载，这不是错误
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                throw new RuntimeException("删除旧数据失败: " + response.getMessage());
            } else {
                logger.info("✓ 已删除文件的旧数据: {}", normalizedPath);
            }

        } catch (Exception e) {
            logger.error("删除旧数据失败: {}", filePath, e);
            throw e;
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertToMilvus(String content, List<Float> vector, 
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            // 生成唯一 ID（使用 _source + 分片索引）
            String source = (String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

            // 构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();
            
            // ID 字段
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            
            // content 字段
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            
            // vector 字段
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            // metadata 字段（JSON 对象）
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    private record PreparedChunk(
            String content,
            List<Float> vector,
            Map<String, Object> metadata,
            int chunkIndex
    ) {
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
