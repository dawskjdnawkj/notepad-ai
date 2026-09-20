package com.notepad.ai.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
public class AiConfiguration {

    /**
     * DashScope 1.0.0.3 的聊天自动配置使用 Spring Boot 提供的 RestClient.Builder。
     * RAG 请求携带的上下文较长，使用较宽松的读取超时，避免 OkHttp 默认超时。
     */
    @Bean
    public RestClientCustomizer dashScopeRestClientCustomizer(
            @Value("${notepad.ai.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${notepad.ai.http.read-timeout:120s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }

    /**
     * DashScope 的 stream() 使用 WebClient，不会经过上面的 RestClientCustomizer。
     * 显式配置响应式客户端，至少保证连接阶段使用与同步调用一致的超时。
     */
    @Bean
    public WebClientCustomizer dashScopeWebClientCustomizer(
            @Value("${notepad.ai.http.connect-timeout:10s}") Duration connectTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        return builder -> builder.clientConnector(connector);
    }

    /**
     * 向量检索是同步操作，不能占用 Tomcat 请求线程后再返回 SSE。
     */
    @Bean(name = "aiStreamExecutor")
    public Executor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        // 队列过长会让请求等待很久；超出容量时由控制器转换为可重试的 SSE 错误。
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("ai-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    public ChatClient chatClient(DashScopeChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是云笔记系统中的智能助手。
                        请使用简洁、准确、易于理解的中文回答用户。
                        """)
                .build();
    }

    /**
     * 本地 SimpleVectorStore；NoteVectorService 负责将内容保存到 JSON 文件并在启动时恢复。
     * 数据规模扩大后可替换为专业向量数据库。
     *
     * 与 {@link PgVectorConfiguration} 里的 PgVectorStore 条件互斥，
     * 任何时刻容器里只有一个 VectorStore bean，注入天然无歧义。
     */
    @Bean
    @ConditionalOnProperty(
            name = "notepad.ai.vector-store.type",
            havingValue = "simple",
            matchIfMissing = true)
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 按 token 切分长笔记，避免整篇笔记生成一个过大的向量。
     */
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(300)
                .withMinChunkSizeChars(20)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .build();
    }
}
