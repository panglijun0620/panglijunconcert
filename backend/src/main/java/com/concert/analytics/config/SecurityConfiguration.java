package com.concert.analytics.config;

import com.concert.analytics.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ObjectMapper objectMapper, SecurityProperties properties) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize
                            .requestMatchers("/", "/login.html", "/annotate.html", "/favicon.ico",
                                    "/css/**", "/js/**", "/assets/**")
                            .permitAll()
                            .requestMatchers("/api/v1/auth/me").authenticated()
                            .requestMatchers("/actuator/health", "/error").permitAll();
                    if (properties.getAdmin().isEnabled()) {
                        authorize.requestMatchers("/api/v1/admin/**", "/h2-console/**").hasRole("ADMIN");
                    } else {
                        authorize.requestMatchers("/api/v1/admin/**").permitAll();
                        authorize.requestMatchers("/h2-console/**").denyAll();
                    }
                    authorize
                            .requestMatchers("/api/v1/**").permitAll()
                            .anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityError(
                                objectMapper, response, request.getRequestURI(), 401,
                                "AUTHENTICATION_REQUIRED", "Administrator authentication is required"))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityError(
                                objectMapper, response, request.getRequestURI(), 403,
                                "ACCESS_DENIED", "Access is denied")))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        if (properties.getAdmin().isEnabled()) {
            http.httpBasic(Customizer.withDefaults());
        }
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties, PasswordEncoder passwordEncoder) {
        if (!properties.getAdmin().isEnabled()) {
            return new InMemoryUserDetailsManager(User.builder()
                    .username("disabled-admin")
                    .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .roles("ADMIN")
                    .build());
        }
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        manager.createUser(User.builder()
                .username(properties.getAdmin().getUsername())
                .password(passwordEncoder.encode(resolvePassword(
                        properties.getAdmin().getPassword(), "ADMIN_PASSWORD")))
                .roles("ADMIN")
                .build());
        manager.createUser(User.builder()
                .username(properties.getViewer().getUsername())
                .password(passwordEncoder.encode(resolvePassword(
                        properties.getViewer().getPassword(), "VIEWER_PASSWORD")))
                .roles("VIEWER")
                .build());
        return manager;
    }

    /** 密码没配就随机生成一个，保证不会被空密码登录；随机值只在本次进程内有效。 */
    private static String resolvePassword(String configured, String propertyName) {
        if (configured == null || configured.isBlank()) {
            log.warn("{} is empty; the account uses an ephemeral password for this process", propertyName);
            return UUID.randomUUID().toString();
        }
        return configured;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(properties.getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(!origins.isEmpty());
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static void writeSecurityError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            String path,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status, code, message, path, Map.of()));
    }
}
