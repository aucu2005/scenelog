package com.scenelog.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ★ 직접 구현 대상 3/3 — 요청마다 토큰을 읽어 인증 정보를 심는다.
 *
 * <p>이 클래스에는 {@code @Component}를 붙이지 않는다. 붙이면 Spring Boot가 서블릿 필터로도 자동 등록해
 * 필터가 두 번 실행된다. 등록은 {@code SecurityConfig}에서 {@code addFilterBefore}로만 한다.
 *
 * <p><b>계약</b>: 인증 성공 시 principal에 {@link User} 엔티티를 넣는다.
 * 컨트롤러가 {@code @AuthenticationPrincipal User user}로 받으므로 이 타입을 지켜야 한다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    /**
     * TODO(직접 구현):
     * <ol>
     *   <li>{@code request.getHeader(HEADER)}를 읽는다.
     *       null이거나 {@code PREFIX}로 시작하지 않으면 <b>아무것도 하지 않고</b> 다음 필터로 넘긴다
     *       (로그인·헬스체크처럼 토큰 없는 요청도 통과해야 하므로 여기서 예외를 던지면 안 된다)</li>
     *   <li>{@code header.substring(PREFIX.length())}로 토큰만 추출</li>
     *   <li>{@code jwtProvider.isValid(token)}이 false면 역시 그냥 통과 (인가 거부는 Security가 처리)</li>
     *   <li>{@code jwtProvider.getUserId(token)} → {@code userRepository.findById(userId)}</li>
     *   <li>사용자를 찾았으면 인증 객체 생성:
     *       <pre>
     *   var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
     *   var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
     *   SecurityContextHolder.getContext().setAuthentication(auth);
     *       </pre>
     *       {@code getRole().name()}은 "ROLE_ADMIN" 형태이고, SecurityConfig의
     *       {@code hasRole("ADMIN")}이 이 값과 맞물린다.</li>
     * </ol>
     *
     * <p>아래 {@code filterChain.doFilter(...)} 호출은 반드시 남긴다.
     * 빠지면 모든 요청이 응답 없이 멈춘다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // TODO: 위 1~5단계를 여기에 구현

        filterChain.doFilter(request, response);
    }
}
