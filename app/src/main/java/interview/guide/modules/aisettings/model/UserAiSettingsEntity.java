package interview.guide.modules.aisettings.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "user_ai_settings",
        indexes = {
                @Index(name = "idx_user_ai_settings_user_id", columnList = "user_id", unique = true)
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAiSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;

    @Column(name = "model_base_url", nullable = false, length = 512)
    private String modelBaseUrl;

    @Column(name = "model_api_key", nullable = false, length = 1024)
    private String modelApiKey;

    @Column(name = "chat_model", nullable = false, length = 128)
    private String chatModel;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "embedding_dimensions")
    private Integer embeddingDimensions;

    @Column(name = "cloud_embedding_base_url", length = 512)
    private String cloudEmbeddingBaseUrl;

    @Column(name = "cloud_embedding_api_key", length = 1024)
    private String cloudEmbeddingApiKey;

    @Column(name = "cloud_embedding_model", length = 128)
    private String cloudEmbeddingModel;

    @Column(name = "cloud_embedding_dimensions")
    private Integer cloudEmbeddingDimensions;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "asr_url", nullable = false, length = 512)
    private String asrUrl;

    @Column(name = "asr_model", nullable = false, length = 128)
    private String asrModel;

    @Column(name = "asr_api_key", length = 1024)
    private String asrApiKey;

    @Column(name = "asr_language", nullable = false, length = 32)
    private String asrLanguage;

    @Column(name = "asr_format", nullable = false, length = 32)
    private String asrFormat;

    @Column(name = "asr_sample_rate", nullable = false)
    private Integer asrSampleRate;

    @Column(name = "asr_enable_turn_detection", nullable = false)
    private Boolean asrEnableTurnDetection;

    @Column(name = "asr_turn_detection_type", nullable = false, length = 64)
    private String asrTurnDetectionType;

    @Column(name = "asr_turn_detection_threshold", nullable = false)
    private Float asrTurnDetectionThreshold;

    @Column(name = "asr_turn_detection_silence_duration_ms", nullable = false)
    private Integer asrTurnDetectionSilenceDurationMs;

    @Column(name = "tts_model", nullable = false, length = 128)
    private String ttsModel;

    @Column(name = "tts_api_key", length = 1024)
    private String ttsApiKey;

    @Column(name = "tts_voice", nullable = false, length = 64)
    private String ttsVoice;

    @Column(name = "tts_format", nullable = false, length = 32)
    private String ttsFormat;

    @Column(name = "tts_sample_rate", nullable = false)
    private Integer ttsSampleRate;

    @Column(name = "tts_mode", nullable = false, length = 32)
    private String ttsMode;

    @Column(name = "tts_language_type", nullable = false, length = 64)
    private String ttsLanguageType;

    @Column(name = "tts_speech_rate", nullable = false)
    private Float ttsSpeechRate;

    @Column(name = "tts_volume", nullable = false)
    private Integer ttsVolume;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
