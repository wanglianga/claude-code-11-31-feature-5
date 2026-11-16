package com.mdm.platform.web;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.DigestUtil;
import com.mdm.platform.security.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JdbcSupport db;
    private final TokenService tokenService;

    public AuthController(JdbcSupport db, TokenService tokenService) {
        this.db = db;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<Map<String, Object>>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        Map<String, Object> user = db.queryOne(
                "SELECT * FROM app_user WHERE username=? AND enabled=TRUE", username);
        if (user == null || !String.valueOf(user.get("password_hash"))
                .equals(DigestUtil.passwordHash(password == null ? "" : password))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, "用户名或密码错误"));
        }
        TokenService.Session session = tokenService.issue(
                String.valueOf(user.get("username")),
                String.valueOf(user.get("display_name")),
                String.valueOf(user.get("role")));
        return ResponseEntity.ok(ApiResult.ok(Map.of(
                "token", session.token(),
                "username", session.username(),
                "displayName", session.displayName(),
                "role", session.role())));
    }
}
