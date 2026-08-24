package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.AIOpsRequest;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class ChatControllerTest {

    @Test
    void clearMissingSessionIsIdempotent() {
        ChatController controller = new ChatController();
        ChatSessionService sessionService = mock(ChatSessionService.class);
        when(sessionService.clearHistory("missing")).thenReturn(false);
        ReflectionTestUtils.setField(controller, "chatSessionService", sessionService);

        ChatController.ClearRequest request = new ChatController.ClearRequest();
        request.setId("missing");

        ResponseEntity<ChatController.ApiResponse<String>> response =
                controller.clearChatHistory(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(200);
        assertThat(response.getBody().getData()).isEqualTo("会话历史已清空");
    }

    @Test
    void deleteMissingSessionIsIdempotent() {
        ChatController controller = new ChatController();
        ChatSessionService sessionService = mock(ChatSessionService.class);
        when(sessionService.deleteSession("missing")).thenReturn(false);
        ReflectionTestUtils.setField(controller, "chatSessionService", sessionService);

        ResponseEntity<ChatController.ApiResponse<String>> response =
                controller.deleteChatSession("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(200);
        assertThat(response.getBody().getData()).isEqualTo("会话已删除");
    }

    @Test
    void sessionQueryReturnsSafeServerErrorWhenPersistenceFails() {
        ChatController controller = new ChatController();
        ChatSessionService sessionService = mock(ChatSessionService.class);
        when(sessionService.findSession("session-1"))
                .thenThrow(new RuntimeException("sensitive database path"));
        ReflectionTestUtils.setField(controller, "chatSessionService", sessionService);

        ResponseEntity<ChatController.ApiResponse<ChatController.SessionInfoResponse>> response =
                controller.getSessionInfo("session-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).isEqualTo("获取会话信息失败");
        assertThat(response.getBody().getMessage()).doesNotContain("sensitive database path");
    }

    @Test
    void chatStreamDoesNotExposePersistenceFailureDetails() throws Exception {
        ChatController controller = new ChatController();
        ChatSessionService sessionService = mock(ChatSessionService.class);
        when(sessionService.beginTurn(eq("session-1"), eq("继续排查"), any()))
                .thenThrow(new RuntimeException("jdbc:h2:file:C:/sensitive/path"));
        ReflectionTestUtils.setField(controller, "chatSessionService", sessionService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(post("/api/chat_stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"session-1\",\"Question\":\"继续排查\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(3000);

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("对话服务暂时不可用，请稍后重试")
                .doesNotContain("jdbc:h2:file", "sensitive/path");
    }

    @Test
    void chatReturnsBadRequestWhenQuestionIsBlank() {
        ChatController controller = new ChatController();
        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setQuestion("   ");

        ResponseEntity<ChatController.ApiResponse<ChatController.ChatResponse>> response =
                controller.chat(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().getMessage()).isEqualTo("问题内容不能为空");
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void chatReturnsSafeServerErrorWhenChatServiceFails() {
        ChatController controller = new ChatController();
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ChatSessionService.SessionTurn sessionTurn = mock(ChatSessionService.SessionTurn.class);
        when(sessionService.beginTurn("session-1", "数据库为什么变慢了？"))
                .thenReturn(sessionTurn);
        when(sessionTurn.history()).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "chatSessionService", sessionService);

        ChatService chatService = mock(ChatService.class);
        when(chatService.createStandardChatModel())
                .thenThrow(new RuntimeException("sensitive provider detail"));
        ReflectionTestUtils.setField(controller, "chatService", chatService);

        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setId("session-1");
        request.setQuestion("数据库为什么变慢了？");

        ResponseEntity<ChatController.ApiResponse<ChatController.ChatResponse>> response =
                controller.chat(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getMessage()).isEqualTo("对话服务暂时不可用，请稍后重试");
        assertThat(response.getBody().getMessage()).doesNotContain("sensitive provider detail");
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void aiOpsPassesUserTaskToService() throws Exception {
        ChatController controller = new ChatController();
        ChatService chatService = mock(ChatService.class);
        DashScopeChatModel chatModel = mock(DashScopeChatModel.class);
        when(chatService.createAiOpsChatModel()).thenReturn(chatModel);
        ReflectionTestUtils.setField(controller, "chatService", chatService);

        AiOpsService aiOpsService = mock(AiOpsService.class);
        when(aiOpsService.executeAiOpsAnalysis(any(DashScopeChatModel.class), eq("排查订单服务")))
                .thenReturn(Optional.empty());
        ReflectionTestUtils.setField(controller, "aiOpsService", aiOpsService);

        AIOpsRequest request = new AIOpsRequest();
        request.setUserRequest("排查订单服务");

        controller.aiOps(request);

        verify(aiOpsService, timeout(3000))
                .executeAiOpsAnalysis(any(DashScopeChatModel.class), eq("排查订单服务"));
    }

    @Test
    void aiOpsRejectsUserTaskLongerThanInputLimit() {
        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "aiOpsService", new AiOpsService());

        AIOpsRequest request = new AIOpsRequest();
        request.setUserRequest("任".repeat(1001));

        assertThatThrownBy(() -> controller.aiOps(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("运维任务不能超过1000个字符");
                });
    }
}
