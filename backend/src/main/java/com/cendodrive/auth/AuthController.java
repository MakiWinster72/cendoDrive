package com.cendodrive.auth;

import com.cendodrive.auth.AuthDtos.*;
import com.cendodrive.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/api/auth/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }
    @PostMapping("/api/auth/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }
    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        auth.logout(authorization.substring(7));
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/user/me")
    UserResponse me(@AuthenticationPrincipal User user) { return UserResponse.from(user); }
}
