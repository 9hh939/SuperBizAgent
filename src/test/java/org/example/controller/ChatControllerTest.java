package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.AIOpsRequest;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

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
        ChatService chatService = mock(ChatService.class);
        when(chatService.createDashScopeApi())
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
        when(chatService.createDashScopeApi()).thenReturn(mock(DashScopeApi.class));
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
