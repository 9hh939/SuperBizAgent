package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorIndexServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void indexSingleFileDoesNotDeleteOldVectorsWhenEmbeddingPreparationFails() throws Exception {
        Path logicalSource = temporaryDirectory.resolve("incident.md");
        Path rollbackFile = temporaryDirectory.resolve("incident.backup");
        Files.writeString(logicalSource, "new knowledge");
        Files.writeString(rollbackFile, "old knowledge");

        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);
        when(chunkService.chunkDocument(anyString(), anyString()))
                .thenReturn(List.of(new DocumentChunk("new knowledge", 0, 13, 0)));
        when(embeddingService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("DashScope unavailable"));

        VectorIndexService service = new VectorIndexService();
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "chunkService", chunkService);

        assertThatThrownBy(() -> service.indexSingleFile(
                logicalSource.toString(), logicalSource.toString(), rollbackFile.toString()))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(milvusClient);
    }

    @Test
    void indexSingleFileRestoresOldVectorsWhenNewInsertFails() throws Exception {
        Path logicalSource = temporaryDirectory.resolve("incident.md");
        Path rollbackFile = temporaryDirectory.resolve("incident.backup");
        Files.writeString(logicalSource, "new knowledge");
        Files.writeString(rollbackFile, "old knowledge");

        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);
        when(chunkService.chunkDocument("new knowledge", logicalSource.toString()))
                .thenReturn(List.of(
                        new DocumentChunk("new chunk 1", 0, 11, 0),
                        new DocumentChunk("new chunk 2", 12, 23, 1)
                ));
        when(chunkService.chunkDocument("old knowledge", logicalSource.toString()))
                .thenReturn(List.of(new DocumentChunk("old chunk", 0, 9, 0)));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1F));

        R<RpcStatus> loadedCollection = R.success();
        R<MutationResult> deleteSuccess = R.success();
        R<MutationResult> insertSuccess = R.success();
        R<MutationResult> insertFailure = new R<>();
        insertFailure.setStatus(1);
        when(milvusClient.loadCollection(any())).thenReturn(loadedCollection);
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(deleteSuccess);
        when(milvusClient.insert(any(InsertParam.class))).thenReturn(insertSuccess, insertFailure, insertSuccess);

        VectorIndexService service = new VectorIndexService();
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "chunkService", chunkService);

        assertThatThrownBy(() -> service.indexSingleFile(
                logicalSource.toString(), logicalSource.toString(), rollbackFile.toString()))
                .isInstanceOf(RuntimeException.class);

        org.mockito.ArgumentCaptor<InsertParam> insertCaptor =
                org.mockito.ArgumentCaptor.forClass(InsertParam.class);
        verify(milvusClient, times(3)).insert(insertCaptor.capture());
        verify(milvusClient, times(2)).delete(any(DeleteParam.class));
        org.assertj.core.api.Assertions.assertThat(insertCaptor.getAllValues())
                .extracting(this::contentOf)
                .containsExactly("new chunk 1", "new chunk 2", "old chunk");
    }

    @Test
    void indexSingleFileStopsBeforeInsertWhenDeletingOldVectorsFails() throws Exception {
        Path logicalSource = temporaryDirectory.resolve("incident.md");
        Files.writeString(logicalSource, "new knowledge");

        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);
        when(chunkService.chunkDocument("new knowledge", logicalSource.toString()))
                .thenReturn(List.of(new DocumentChunk("new chunk", 0, 9, 0)));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1F));
        when(milvusClient.loadCollection(any()))
                .thenReturn(R.failed(new RuntimeException("Milvus unavailable")), R.success());
        when(milvusClient.insert(any(InsertParam.class))).thenReturn(R.success());

        VectorIndexService service = new VectorIndexService();
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "chunkService", chunkService);

        assertThatThrownBy(() -> service.indexSingleFile(
                logicalSource.toString(), logicalSource.toString(), null))
                .isInstanceOf(RuntimeException.class);

        verify(milvusClient, never()).insert(any(InsertParam.class));
    }

    @Test
    void indexSingleFileDoesNotMixOldVectorsWhenPartialNewVectorCleanupFails() throws Exception {
        Path logicalSource = temporaryDirectory.resolve("incident.md");
        Path rollbackFile = temporaryDirectory.resolve("incident.backup");
        Files.writeString(logicalSource, "new knowledge");
        Files.writeString(rollbackFile, "old knowledge");

        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);
        when(chunkService.chunkDocument("new knowledge", logicalSource.toString()))
                .thenReturn(List.of(
                        new DocumentChunk("new chunk 1", 0, 11, 0),
                        new DocumentChunk("new chunk 2", 12, 23, 1)
                ));
        when(chunkService.chunkDocument("old knowledge", logicalSource.toString()))
                .thenReturn(List.of(new DocumentChunk("old chunk", 0, 9, 0)));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1F));

        R<MutationResult> insertFailure = new R<>();
        insertFailure.setStatus(1);
        when(milvusClient.loadCollection(any())).thenReturn(R.success());
        when(milvusClient.delete(any(DeleteParam.class)))
                .thenReturn(R.success(), R.failed(new RuntimeException("cleanup unavailable")));
        when(milvusClient.insert(any(InsertParam.class)))
                .thenReturn(R.success(), insertFailure);

        VectorIndexService service = new VectorIndexService();
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "chunkService", chunkService);

        assertThatThrownBy(() -> service.indexSingleFile(
                logicalSource.toString(), logicalSource.toString(), rollbackFile.toString()))
                .isInstanceOf(RuntimeException.class);

        verify(milvusClient, times(2)).insert(any(InsertParam.class));
        verify(milvusClient, times(2)).delete(any(DeleteParam.class));
    }

    @Test
    void indexSingleFileCleansPartialOldVectorsWhenRestorationInsertFails() throws Exception {
        Path logicalSource = temporaryDirectory.resolve("incident.md");
        Path rollbackFile = temporaryDirectory.resolve("incident.backup");
        Files.writeString(logicalSource, "new knowledge");
        Files.writeString(rollbackFile, "old knowledge");

        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);
        when(chunkService.chunkDocument("new knowledge", logicalSource.toString()))
                .thenReturn(List.of(
                        new DocumentChunk("new chunk 1", 0, 11, 0),
                        new DocumentChunk("new chunk 2", 12, 23, 1)
                ));
        when(chunkService.chunkDocument("old knowledge", logicalSource.toString()))
                .thenReturn(List.of(
                        new DocumentChunk("old chunk 1", 0, 11, 0),
                        new DocumentChunk("old chunk 2", 12, 23, 1)
                ));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1F));

        R<MutationResult> insertFailure = new R<>();
        insertFailure.setStatus(1);
        when(milvusClient.loadCollection(any())).thenReturn(R.success());
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(R.success());
        when(milvusClient.insert(any(InsertParam.class)))
                .thenReturn(R.success(), insertFailure, R.success(), insertFailure);

        VectorIndexService service = new VectorIndexService();
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "chunkService", chunkService);

        assertThatThrownBy(() -> service.indexSingleFile(
                logicalSource.toString(), logicalSource.toString(), rollbackFile.toString()))
                .isInstanceOf(RuntimeException.class);

        verify(milvusClient, times(4)).insert(any(InsertParam.class));
        verify(milvusClient, times(3)).delete(any(DeleteParam.class));
    }

    private String contentOf(InsertParam insertParam) {
        return insertParam.getFields().stream()
                .filter(field -> "content".equals(field.getName()))
                .findFirst()
                .orElseThrow()
                .getValues()
                .get(0)
                .toString();
    }
}
