package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import okhttp3.OkHttpClient;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * DashScope API 配置
 * 用于配置超时时间等参数
 */
@Configuration
public class DashScopeConfig {

    @Value("${dashscope.http.connect-timeout:10s}")
    private Duration connectTimeout;

    @Value("${dashscope.http.response-timeout:180s}")
    private Duration responseTimeout;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    OkHttpClient dashScopeOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(responseTimeout)
                .writeTimeout(responseTimeout)
                .callTimeout(responseTimeout)
                .build();
    }

    /**
     * 为 DashScope 同步调用创建专用 RestClient.Builder。
     */
    RestClient.Builder restClientBuilder(OkHttpClient dashScopeOkHttpClient) {
        // 创建 RestClient.Builder 并配置 OkHttpClient
        return RestClient.builder()
                .requestFactory(new OkHttp3ClientHttpRequestFactory(dashScopeOkHttpClient));
    }

    WebClient.Builder dashScopeWebClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    @Bean
    public DashScopeApi dashScopeApi() {
        OkHttpClient okHttpClient = dashScopeOkHttpClient();
        RestClient.Builder restClientBuilder = restClientBuilder(okHttpClient);
        WebClient.Builder webClientBuilder = dashScopeWebClientBuilder();

        return DashScopeApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
    }
}
