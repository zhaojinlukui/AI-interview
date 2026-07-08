package interview.guide.common.config;

import interview.guide.common.ai.AiClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class LlmEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(AiClientFactory clientFactory) {
        log.info("嵌入模型 bean 已作为 AI 模型委托初始化");
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return clientFactory.getEmbeddingModel().call(request);
            }

            @Override
            public float[] embed(Document document) {
                return clientFactory.getEmbeddingModel().embed(document);
            }
        };
    }
}
