package interview.guide.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.aisettings.dto.AsrConfigDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.InterviewParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.RagSearchParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.ResumeWeightsDTO;
import interview.guide.modules.aisettings.dto.TtsConfigDTO;
import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.service.AiSettingsResolver.AsrConfigSnapshot;
import interview.guide.modules.aisettings.service.AiSettingsResolver.ModelConfigSnapshot;
import interview.guide.modules.aisettings.service.AiSettingsResolver.TtsConfigSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.InterviewSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.RagSearchSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.ResumeWeightsSnapshot;
import interview.guide.modules.aisettings.service.SystemAiSettingsResolver.SystemAiSettingsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("AI 设置映射器")
class AiSettingsMapperTest {

    private final AiSettingsMapper mapper = Mappers.getMapper(AiSettingsMapper.class);

    @Nested
    @DisplayName("用户配置响应")
    class UserSettingsResponse {

        @Test
        @DisplayName("模型配置转换时脱敏 API Key")
        void masksApiKeysWhenMappingModelSettings() {
            ModelConfigSnapshot model = new ModelConfigSnapshot(
                    "https://example.com/v1",
                    "sk-1234567890",
                    "qwen-plus",
                    "unused",
                    1024,
                    0.2
            );
            ModelConfigSnapshot embedding = new ModelConfigSnapshot(
                    "https://embedding.example.com/v1",
                    "emb-abcdef1234",
                    "qwen-plus",
                    "text-embedding-v4",
                    1536,
                    0.2
            );

            var dto = mapper.toModelSettingsDTO(model, embedding);

            assertThat(dto.maskedApiKey()).isEqualTo("sk-1****7890");
            assertThat(dto.maskedCloudEmbeddingApiKey()).isEqualTo("emb-****1234");
            assertThat(dto.cloudEmbeddingModel()).isEqualTo("text-embedding-v4");
            assertThat(dto.cloudEmbeddingDimensions()).isEqualTo(1536);
        }

        @Test
        @DisplayName("ASR 空数值字段转换为 primitive 默认值")
        void mapsAsrNullNumbersToZero() {
            AsrConfigDTO dto = mapper.toAsrConfigDTO(new AsrConfigSnapshot(
                    "wss://asr.example.com",
                    "asr-model",
                    "secret",
                    "zh",
                    "pcm",
                    null,
                    true,
                    "server_vad",
                    null,
                    null
            ));

            assertThat(dto.getSampleRate()).isZero();
            assertThat(dto.getTurnDetectionThreshold()).isZero();
            assertThat(dto.getTurnDetectionSilenceDurationMs()).isZero();
            assertThat(dto.getMaskedApiKey()).isEqualTo("****");
        }

        @Test
        @DisplayName("TTS 空数值字段转换为 primitive 默认值")
        void mapsTtsNullNumbersToZero() {
            TtsConfigDTO dto = mapper.toTtsConfigDTO(new TtsConfigSnapshot(
                    "tts-model",
                    "sk-12345678",
                    "Cherry",
                    "wav",
                    null,
                    "cosyvoice-v1",
                    "Chinese",
                    null,
                    null
            ));

            assertThat(dto.getSampleRate()).isZero();
            assertThat(dto.getSpeechRate()).isZero();
            assertThat(dto.getVolume()).isZero();
            assertThat(dto.getMaskedApiKey()).isEqualTo("sk-1****5678");
        }
    }

    @Nested
    @DisplayName("系统参数")
    class SystemSettings {

        @Test
        @DisplayName("系统参数快照完整转换为 DTO")
        void mapsSystemSnapshotToDto() {
            SystemAiSettingsSnapshot snapshot = systemSnapshot();

            SystemAiParametersDTO dto = mapper.toSystemAiParametersDTO(snapshot);

            assertThat(dto.resumeWeights()).isEqualTo(new ResumeWeightsDTO(40, 20, 15, 15, 10));
            assertThat(dto.interview()).isEqualTo(new InterviewParametersDTO(60, 40, 0.2, 0.3, 0.4, 0.5));
            assertThat(dto.ragSearch()).isEqualTo(new RagSearchParametersDTO(20, 12, 8, 0.18, 0.28, 0.38));
        }

        @Test
        @DisplayName("系统参数 DTO 完整更新实体")
        void updatesSystemEntityFromDto() {
            SystemAiSettingsEntity entity = new SystemAiSettingsEntity();
            SystemAiParametersDTO request = new SystemAiParametersDTO(
                    new ResumeWeightsDTO(35, 25, 15, 15, 10),
                    new InterviewParametersDTO(70, 30, 0.3, 0.4, 0.5, 0.6),
                    new RagSearchParametersDTO(10, 11, 12, 0.1, 0.2, 0.3)
            );

            mapper.updateSystemAiSettingsEntity(request, entity);

            assertThat(entity.getResumeProjectWeight()).isEqualTo(35);
            assertThat(entity.getInterviewResumeQuestionRatio()).isEqualTo(70);
            assertThat(entity.getInterviewCommentTemperature()).isEqualTo(0.6);
            assertThat(entity.getRagTopkLong()).isEqualTo(12);
            assertThat(entity.getRagMinScoreLong()).isEqualTo(0.3);
        }

        private SystemAiSettingsSnapshot systemSnapshot() {
            return new SystemAiSettingsSnapshot(
                    new ResumeWeightsSnapshot(40, 20, 15, 15, 10),
                    new InterviewSnapshot(60, 40, 0.2, 0.3, 0.4, 0.5),
                    new RagSearchSnapshot(20, 12, 8, 0.18, 0.28, 0.38)
            );
        }
    }
}
