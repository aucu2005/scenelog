package com.scenelog.common.config;

import com.scenelog.auth.JwtAuthenticationFilter;
import com.scenelog.auth.JwtProvider;
import com.scenelog.auth.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * DelegatingPasswordEncoder — 저장 시 "{bcrypt}" 접두어가 붙는다.
     * 나중에 더 강한 알고리즘으로 갈아탈 때 기존 해시를 그대로 두고 점진 전환할 수 있는 구조이며,
     * 접두어 때문에 저장 길이가 68자가 되므로 users.password_hash를 VARCHAR(100)으로 잡았다 (기획서 §5.1).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * JwtAuthenticationFilter는 @Component가 아니라 여기서 직접 생성한다.
     * @Component를 붙이면 Spring Boot가 서블릿 필터로도 자동 등록해 필터가 두 번 실행된다.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtProvider jwtProvider,
                                           UserRepository userRepository) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtProvider, userRepository);

        http
                .csrf(csrf -> csrf.disable())            // JWT 기반 무상태 API — 세션 CSRF 불필요
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/api/auth/**",
                                "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 관리자 전용 — ETL 트리거·품질 리포트·집계·시뮬레이터 (기획서 §6)
                        // 무인증이면 외부에서 ETL을 무한 트리거할 수 있으므로 반드시 막는다
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/contents/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
