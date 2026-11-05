package com.officemanipulator.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes the API key scheme WebConfig enforces, so Swagger UI's "Authorize" button works. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI yaverOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Yaver API").version("1.0")
                        .description("Doğal dildeki istekten Word, PowerPoint, PDF, Excel ve CSV belgeleri üretir, önizler, düzenler ve indirir. "
                                + "Her istek `Authorization: Bearer <YAVER_API_KEYS içindeki anahtar>` başlığı taşımalıdır."))
                .components(new Components().addSecuritySchemes("apiKey",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").description("YAVER_API_KEYS içindeki anahtarlardan biri")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
