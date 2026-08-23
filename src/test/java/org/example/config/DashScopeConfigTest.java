package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DashScopeConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DashScopeConfig.class)
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withPropertyValues(
                    "spring.ai.dashscope.api-key=test-key",
                    "dashscope.http.connect-timeout=10s",
                    "dashscope.http.response-timeout=180s"
            );

    @Test
    void blockingClientUsesSeparateConnectAndResponseTimeouts() {
        DashScopeConfig config = new DashScopeConfig();
        ReflectionTestUtils.setField(config, "connectTimeout", Duration.ofSeconds(10));
        ReflectionTestUtils.setField(config, "responseTimeout", Duration.ofSeconds(180));

        OkHttpClient client = config.dashScopeOkHttpClient();

        assertThat(client.connectTimeoutMillis()).isEqualTo(10_000);
        assertThat(client.readTimeoutMillis()).isEqualTo(180_000);
        assertThat(client.writeTimeoutMillis()).isEqualTo(180_000);
        assertThat(client.callTimeoutMillis()).isEqualTo(180_000);
    }

    @Test
    void streamingClientStopsWaitingForAnUnresponsiveServer() throws IOException {
        DashScopeConfig config = new DashScopeConfig();
        ReflectionTestUtils.setField(config, "connectTimeout", Duration.ofSeconds(1));
        ReflectionTestUtils.setField(config, "responseTimeout", Duration.ofMillis(100));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1_000);
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            Throwable thrown = catchThrowable(() -> config.dashScopeWebClientBuilder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build()
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block());

            assertThat(thrown).isNotNull();
            assertThat(rootCause(thrown).getClass().getSimpleName()).isEqualTo("ReadTimeoutException");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesOnlyTheConfiguredDashScopeApiAsAClientBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DashScopeApi.class);
            assertThat(context).doesNotHaveBean(OkHttpClient.class);
            assertThat(context).doesNotHaveBean(RestClient.Builder.class);
            assertThat(context).doesNotHaveBean(WebClient.Builder.class);
        });
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
