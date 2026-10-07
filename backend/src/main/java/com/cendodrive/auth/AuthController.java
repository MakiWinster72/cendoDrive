package com.cendodrive.auth;

import com.cendodrive.auth.AuthDtos.*;
import com.cendodrive.common.ApiError;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.cendodrive.user.User;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "认证与用户", description = "账号注册、登录、退出以及当前用户查询")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @Operation(summary = "注册用户", description = "用户名仅允许 3–64 位英文字母、数字或下划线；密码为 8–128 位。")
  @ApiResponses({ @ApiResponse(responseCode = "201", description = "注册成功"),
      @ApiResponse(responseCode = "400", description = "字段校验失败", content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "用户名冲突", content = @Content(schema = @Schema(implementation = ApiError.class))) })
  @PostMapping("/api/auth/register")
  ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
  }

  @Operation(summary = "登录", description = "返回有效期 86400 秒的随机 Token；不使用 Cookie。")
  @ApiResponses({ @ApiResponse(responseCode = "200", description = "登录成功"),
      @ApiResponse(responseCode = "400", description = "输入不合法", content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "401", description = "账号或密码错误、账号禁用", content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "429", description = "该用户名 15 分钟内失败次数达到 5 次", content = @Content(schema = @Schema(implementation = ApiError.class))) })
  @PostMapping("/api/auth/login")
  LoginResponse login(@Valid @RequestBody LoginRequest request) {
    return auth.login(request);
  }

  @Operation(summary = "退出当前登录", description = "撤销当前 Bearer Token，不影响其他设备。")
  @SecurityRequirement(name = "bearerAuth")
  @ApiResponses({ @ApiResponse(responseCode = "204", description = "已退出", content = @Content),
      @ApiResponse(responseCode = "401", description = "未认证或凭证失效", content = @Content(schema = @Schema(implementation = ApiError.class))) })
  @PostMapping("/api/auth/logout")
  ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
    auth.logout(authorization.substring(7));
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "获取当前用户")
  @SecurityRequirement(name = "bearerAuth")
  @ApiResponses({ @ApiResponse(responseCode = "200", description = "用户摘要"),
      @ApiResponse(responseCode = "401", description = "未认证或凭证失效", content = @Content(schema = @Schema(implementation = ApiError.class))) })
  @GetMapping("/api/user/me")
  UserResponse me(@AuthenticationPrincipal User user) {
    return UserResponse.from(user);
  }
}
