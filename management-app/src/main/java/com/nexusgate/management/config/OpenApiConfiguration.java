package com.nexusgate.management.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI managementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nexus Hub Management API")
                        .version("v1")
                        .description("""
                                API for organization registration, user and authentication management,
                                registered services, and API-key lifecycle operations. This specification
                                covers the Management App only; integration with the Nexus Hub Gateway
                                is future work.
                                """))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste an access JWT. Swagger sends it as Authorization: Bearer <token>.")));
    }
}
