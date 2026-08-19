package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.service.VectorIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class FileUploadControllerTest {

    @TempDir
    Path uploadDirectory;

    @Test
    void uploadReturnsServerErrorWhenVectorIndexingFails() throws Exception {
        FileUploadController controller = new FileUploadController();
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setPath(uploadDirectory.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = mock(VectorIndexService.class);
        doThrow(new RuntimeException("Milvus unavailable"))
                .when(vectorIndexService)
                .indexSingleFile(anyString(), anyString(), any());

        ReflectionTestUtils.setField(controller, "fileUploadConfig", uploadConfig);
        ReflectionTestUtils.setField(controller, "vectorIndexService", vectorIndexService);

        MockMultipartFile file = new MockMultipartFile(
                "file", "incident.md", "text/markdown", "database is slow".getBytes());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void uploadCommitsNewFileBeforeIndexingAndRestoresOldFileWhenIndexingFails() throws Exception {
        Path existingFile = uploadDirectory.resolve("incident.md");
        Files.writeString(existingFile, "old knowledge");

        FileUploadController controller = new FileUploadController();
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setPath(uploadDirectory.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = mock(VectorIndexService.class);
        doAnswer(invocation -> {
            Path contentPath = Path.of(invocation.getArgument(0, String.class));
            Path logicalSource = Path.of(invocation.getArgument(1, String.class));
            Path rollbackPath = Path.of(invocation.getArgument(2, String.class));

            assertThat(contentPath).isEqualTo(existingFile);
            assertThat(logicalSource).isEqualTo(existingFile);
            assertThat(Files.readString(contentPath)).isEqualTo("new knowledge");
            assertThat(Files.readString(rollbackPath)).isEqualTo("old knowledge");
            throw new RuntimeException("Milvus unavailable");
        })
                .when(vectorIndexService)
                .indexSingleFile(anyString(), anyString(), anyString());

        ReflectionTestUtils.setField(controller, "fileUploadConfig", uploadConfig);
        ReflectionTestUtils.setField(controller, "vectorIndexService", vectorIndexService);

        MockMultipartFile replacement = new MockMultipartFile(
                "file", "incident.md", "text/markdown", "new knowledge".getBytes());

        ResponseEntity<?> response = controller.upload(replacement);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(Files.readString(existingFile)).isEqualTo("old knowledge");
        assertThat(response.getBody()).isInstanceOf(FileUploadController.ApiResponse.class);
        FileUploadController.ApiResponse<?> responseBody =
                (FileUploadController.ApiResponse<?>) response.getBody();
        assertThat(responseBody.getMessage()).isEqualTo("知识库入库失败，请稍后重试");
        assertThat(responseBody.getMessage()).doesNotContain("Milvus unavailable");

        try (var files = Files.list(uploadDirectory)) {
            assertThat(files.map(Path::getFileName).toList())
                    .containsExactly(Path.of("incident.md"));
        }
    }

    @Test
    void uploadRemovesNewFileWhenFirstIndexingAttemptFails() throws Exception {
        FileUploadController controller = new FileUploadController();
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setPath(uploadDirectory.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = mock(VectorIndexService.class);
        doThrow(new RuntimeException("Milvus unavailable"))
                .when(vectorIndexService)
                .indexSingleFile(anyString(), anyString(), any());

        ReflectionTestUtils.setField(controller, "fileUploadConfig", uploadConfig);
        ReflectionTestUtils.setField(controller, "vectorIndexService", vectorIndexService);

        MockMultipartFile file = new MockMultipartFile(
                "file", "new-document.md", "text/markdown", "new knowledge".getBytes());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(uploadDirectory.resolve("new-document.md")).doesNotExist();
        try (var files = Files.list(uploadDirectory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void uploadsWithSameFilenameAreProcessedOneAtATime() throws Exception {
        FileUploadController controller = new FileUploadController();
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setPath(uploadDirectory.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = mock(VectorIndexService.class);
        CountDownLatch firstIndexStarted = new CountDownLatch(1);
        CountDownLatch allowFirstIndexToFinish = new CountDownLatch(1);
        CountDownLatch secondIndexStarted = new CountDownLatch(1);
        AtomicInteger invocationNumber = new AtomicInteger();
        doAnswer(invocation -> {
            if (invocationNumber.incrementAndGet() == 1) {
                firstIndexStarted.countDown();
                assertThat(allowFirstIndexToFinish.await(2, TimeUnit.SECONDS)).isTrue();
            } else {
                secondIndexStarted.countDown();
            }
            return null;
        }).when(vectorIndexService).indexSingleFile(anyString(), anyString(), any());

        ReflectionTestUtils.setField(controller, "fileUploadConfig", uploadConfig);
        ReflectionTestUtils.setField(controller, "vectorIndexService", vectorIndexService);

        MockMultipartFile first = new MockMultipartFile(
                "file", "incident.md", "text/markdown", "first version".getBytes());
        MockMultipartFile second = new MockMultipartFile(
                "file", "incident.md", "text/markdown", "second version".getBytes());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<?>> firstResponse = executor.submit(() -> controller.upload(first));
            assertThat(firstIndexStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<?>> secondResponse = executor.submit(() -> controller.upload(second));
            assertThat(secondIndexStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();

            allowFirstIndexToFinish.countDown();
            assertThat(firstResponse.get(2, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(secondResponse.get(2, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(Files.readString(uploadDirectory.resolve("incident.md")))
                    .isEqualTo("second version");
        } finally {
            allowFirstIndexToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void uploadRejectsFilenameOutsideConfiguredUploadDirectory() throws Exception {
        Path configuredUploadDirectory = uploadDirectory.resolve("uploads");
        Files.createDirectories(configuredUploadDirectory);

        FileUploadController controller = new FileUploadController();
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setPath(configuredUploadDirectory.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        ReflectionTestUtils.setField(controller, "fileUploadConfig", uploadConfig);
        ReflectionTestUtils.setField(controller, "vectorIndexService", mock(VectorIndexService.class));

        MockMultipartFile file = new MockMultipartFile(
                "file", "../escaped.md", "text/markdown", "must stay inside uploads".getBytes());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploadDirectory.resolve("escaped.md")).doesNotExist();
    }
}
