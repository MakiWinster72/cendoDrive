package com.cendodrive.ai.claw;

import com.cendodrive.ai.AiChatService.AiFailure;
import org.springframework.http.HttpStatus;

public final class ClawFailure extends AiFailure {
  public ClawFailure(String code) {
    super(statusFor(code), code, messageFor(code));
  }

  private static HttpStatus statusFor(String code) {
    return switch (code) {
      case "CLAW_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
      case "CLAW_FILE_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
      case "CLAW_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
      case "CLAW_INVALID_QUESTION", "CLAW_CONSENT_REQUIRED", "CLAW_REINDEX_TOO_LARGE", "CLAW_TOO_MANY_FILES" ->
        HttpStatus.BAD_REQUEST;
      case "CLAW_UNSUPPORTED", "CLAW_TOO_LARGE", "CLAW_NO_TEXT" -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.BAD_GATEWAY;
    };
  }

  private static String messageFor(String code) {
    return switch (code) {
      case "CLAW_NOT_CONFIGURED" -> "扣扣Claw尚未配置，请联系管理员。";
      case "CLAW_BUSY" -> "内容问答正在繁忙，请稍后重试。";
      case "CLAW_INVALID_QUESTION" -> "问题不能为空且最多2000字符，上一个问题最多300字符。";
      case "CLAW_CONSENT_REQUIRED" -> "请先确认将文档内容发送到配置的向量模型。";
      case "CLAW_REINDEX_TOO_LARGE" -> "一次最多重建200个文档，请使用单文件重建。";
      case "CLAW_TOO_MANY_FILES" -> "当前最多检索5000个已索引文档，请减少检索范围。";
      case "CLAW_ANSWER_INVALID" -> "模型未返回有效的文档回答，请重试。";
      case "CLAW_FILE_UNAVAILABLE" -> "文件不可用于内容检索。";
      case "CLAW_UNSUPPORTED" -> "暂不支持此文档格式。";
      case "CLAW_TOO_LARGE" -> "文档超过索引限制，请拆分后重试。";
      case "CLAW_NO_TEXT" -> "文档没有可提取文字，扫描件暂不支持。";
      case "CLAW_EMBEDDING_INVALID" -> "向量模型返回格式或维度不匹配，请检查配置。";
      case "CLAW_VECTOR_INVALID" -> "向量数据库配置或响应不匹配，请联系管理员。";
      case "CLAW_VECTOR_UNAVAILABLE" -> "向量数据库暂不可用，请稍后重试。";
      case "CLAW_EMBEDDING_UNAVAILABLE" -> "向量模型暂不可用，请稍后重试。";
      default -> "文档处理失败，请重试或联系管理员。";
    };
  }
}
