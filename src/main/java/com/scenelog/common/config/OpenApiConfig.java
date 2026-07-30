package com.scenelog.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI에 Authorize 버튼을 띄워 발급받은 JWT를 넣고 API를 시험할 수 있게 한다.
 * 프론트엔드가 없으므로 이 화면이 곧 시연 도구다 (기획서 §8-A-0).
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI sceneLogOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SceneLog API")
                        .version("v0.1")
                        .description("콘텐츠 시간축 반응 데이터의 수집·집계·하이라이트 검출 백엔드"))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .name(SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
