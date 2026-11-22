package ru.bcmanager.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание OpenAPI для Swagger UI (Plan.md, раздел 5).
 *
 * <p>Добавляет схему авторизации Bearer JWT, чтобы эндпоинты можно было
 * проверять прямо из браузера.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * Метаданные API и схема авторизации.
     *
     * @return описание OpenAPI
     */
    @Bean
    public OpenAPI bcManagerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BC-Manager API")
                        .version("1.0.0")
                        .description("Управление коммерческой недвижимостью бизнес-центра"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(
                        BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
