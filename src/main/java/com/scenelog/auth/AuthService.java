package com.scenelog.auth;

import com.scenelog.auth.dto.LoginRequest;
import com.scenelog.auth.dto.SignupRequest;
import com.scenelog.auth.dto.TokenResponse;
import com.scenelog.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★ 직접 구현 대상 2/3 — 회원가입·로그인.
 *
 * <p>검증 방법: 앱 실행 후 Swagger UI에서 signup → login 순서로 호출 (docs/dev-log.md의 체크리스트 참고)
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    /**
     * 회원가입.
     *
     * <p>TODO(직접 구현):
     * <ol>
     *   <li>{@code userRepository.existsByEmail(request.email())} → true면
     *       {@code throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다")}</li>
     *   <li>{@code passwordEncoder.encode(request.password())}로 해싱
     *       — 평문을 절대 저장하지 않는다</li>
     *   <li>{@code User.builder().email(...).passwordHash(...).nickname(...).role(Role.ROLE_USER).build()}</li>
     *   <li>{@code userRepository.save(user)}</li>
     * </ol>
     */
    @Transactional
    public void signup(SignupRequest request) {
        throw new UnsupportedOperationException("TODO: 직접 구현");
    }

    /**
     * 로그인 → Access Token 발급.
     *
     * <p>TODO(직접 구현):
     * <ol>
     *   <li>{@code userRepository.findByEmail(request.email())}</li>
     *   <li>없거나 {@code passwordEncoder.matches(request.password(), user.getPasswordHash())}가 false면
     *       {@code throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다")}
     *       <br><b>중요</b>: "없는 계정"과 "비밀번호 틀림"을 구분해 응답하지 않는다.
     *       구분하면 공격자가 가입된 이메일 목록을 알아낼 수 있다(계정 열거 취약점).</li>
     *   <li>{@code jwtProvider.createToken(user.getUserId(), user.getEmail(), user.getRole())}</li>
     *   <li>{@code TokenResponse.bearer(token, jwtProvider.getExpirySeconds())} 반환</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        throw new UnsupportedOperationException("TODO: 직접 구현");
    }
}
