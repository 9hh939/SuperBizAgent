package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalDocsToolsTest {

    @Test
    void queryInternalDocsReturnsResultsFromVectorSearchService() throws Exception {
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);
        VectorSearchService.SearchResult searchResult = new VectorSearchService.SearchResult();
        searchResult.setId("doc-1");
        searchResult.setContent("先检查数据库连接池使用率");
        searchResult.setScore(0.25F);
        searchResult.setMetadata("database-runbook.md");
        when(vectorSearchService.searchSimilarDocuments("数据库连接耗尽", 5))
                .thenReturn(List.of(searchResult));

        InternalDocsTools tools = new InternalDocsTools(vectorSearchService);
        ReflectionTestUtils.setField(tools, "topK", 5);

        JsonNode results = new ObjectMapper().readTree(
                tools.queryInternalDocs("数据库连接耗尽")
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("id").asText()).isEqualTo("doc-1");
        assertThat(results.get(0).get("content").asText())
                .isEqualTo("先检查数据库连接池使用率");
        assertThat(results.get(0).get("metadata").asText())
                .isEqualTo("database-runbook.md");
    }
}
