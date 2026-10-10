package com.cendodrive.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class AiChatDtos {
  private AiChatDtos() {
  }

  public record Message(
      @NotNull @Pattern(regexp = "user|assistant") String role,
      @NotBlank @Size(max = 8000) String content) {
  }

  public record ChatRequest(
      @NotEmpty @Size(max = 41) List<@NotNull @Valid Message> messages) {
  }

  public record ChatResponse(String content) {
  }

  public record StatusResponse(boolean configured, String model) {
  }
}
