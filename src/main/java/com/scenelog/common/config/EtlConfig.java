package com.scenelog.common.config;

import com.scenelog.etl.ContentTransformer;
import com.scenelog.etl.ContentValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 순수 로직 클래스의 빈 등록.
 *
 * <p>{@link ContentValidator}·{@link ContentTransformer}에 {@code @Component}를 붙이지 않은 이유:
 * Spring 없이도 {@code new}로 만들어 테스트할 수 있어야 하기 때문이다.
 * 프레임워크 의존을 로직 밖으로 밀어내면 테스트가 빨라지고 단순해진다.
 */
@Configuration
public class EtlConfig {

    /** 시계를 주입 가능하게 두면 "미래 날짜" 검증을 고정 시각으로 테스트할 수 있다 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public ContentValidator contentValidator(Clock clock) {
        return new ContentValidator(clock);
    }

    @Bean
    public ContentTransformer contentTransformer() {
        return new ContentTransformer();
    }
}
