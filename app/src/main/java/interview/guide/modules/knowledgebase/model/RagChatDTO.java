package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

public class RagChatDTO {

  public record CreateSessionRequest(
      @NotEmpty(message = "At least one knowledge base is required")
      List<Long> knowledgeBaseIds,
      String title
  ) {
  }

  public record SendMessageRequest(
      @NotBlank(message = "Question cannot be blank")
      String question
  ) {
  }

  public record UpdateTitleRequest(
      @NotBlank(message = "Title cannot be blank")
      String title
  ) {
  }

  public record SessionDTO(
      Long id,
      String title,
      List<Long> knowledgeBaseIds,
      LocalDateTime createdAt
  ) {
  }

  public record SessionListItemDTO(
      Long id,
      String title,
      Integer messageCount,
      List<String> knowledgeBaseNames,
      LocalDateTime updatedAt,
      Boolean isPinned
  ) {
  }

  public record SessionDetailDTO(
      Long id,
      String title,
      List<KnowledgeBaseListItemDTO> knowledgeBases,
      List<MessageDTO> messages,
      LocalDateTime createdAt,
      LocalDateTime updatedAt
  ) {
  }

  public record MessageDTO(
      Long id,
      String type,
      String content,
      LocalDateTime createdAt
  ) {
  }
}