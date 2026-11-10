package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录账号信息。
 *
 * <p>登录页用 Basic 认证调这个接口验证账号密码，成功后按返回的角色决定显示哪些入口。
 * 账号本身由 Spring Security 的内存用户存储管理，不存在用户注册表。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/me")
    public AuthUser me(Authentication authentication) {
        String username = authentication == null ? "" : authentication.getName();
        String role = "VIEWER";
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                String value = authority.getAuthority();
                if (value.startsWith("ROLE_")) {
                    role = value.substring(5);
                    break;
                }
            }
        }
        boolean admin = "ADMIN".equals(role);
        return new AuthUser(username, role, admin, admin ? "管理员" : "浏览用户");
    }
}
