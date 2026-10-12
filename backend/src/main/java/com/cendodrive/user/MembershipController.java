package com.cendodrive.user;

import com.cendodrive.auth.AuthDtos.UserResponse;
import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Explicit demo purchase. No real payment, expiry or storage/transfer entitlement. */
@RestController
@RequestMapping("/api/membership")
public class MembershipController {
  private static final Map<String, String> PRICES = Map.of("trial", "5.8", "year", "152", "month", "0.6");
  private final UserRepository users;
  private final boolean enabled;
  public MembershipController(UserRepository users,
      @Value("${cendo.membership.simulated-payment-enabled:true}") boolean enabled) {
    this.users = users;
    this.enabled = enabled;
  }
  public record PaymentRequest(@NotBlank String planId,
      @NotBlank @Pattern(regexp = "wechat") String paymentMethod) { }
  public record PaymentResponse(String amount, String paymentMethod, boolean simulated, UserResponse user) { }

  @PostMapping("/simulate-payment")
  @Transactional
  public PaymentResponse pay(@AuthenticationPrincipal User principal, @Valid @RequestBody PaymentRequest request) {
    if (!enabled) throw new DriveFailure(HttpStatus.NOT_FOUND, "SIMULATED_PAYMENT_DISABLED", "模拟支付未启用");
    String amount = PRICES.get(request.planId());
    if (amount == null) throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_PLAN", "请选择有效的演示套餐");
    User user = users.lock(principal.getId()).orElseThrow(() -> new DriveFailure(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在"));
    if (!user.isActive()) throw new DriveFailure(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "账号不可用");
    user.activateSimulatedMembership();
    users.saveAndFlush(user);
    return new PaymentResponse(amount, "wechat", true, UserResponse.from(user));
  }
}
