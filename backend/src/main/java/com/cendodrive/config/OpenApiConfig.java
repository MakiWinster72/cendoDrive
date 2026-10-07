package com.cendodrive.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI cendoDriveOpenApi() {
    return new OpenAPI()
        .info(new Info().title("CendoDrive API").version("0.1.0")
            .description("认证与文件管理接口；时间统一按 UTC 返回，ID 在响应中使用字符串。"))
        .schemaRequirement("bearerAuth", new SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("opaque token"));
  }
}
