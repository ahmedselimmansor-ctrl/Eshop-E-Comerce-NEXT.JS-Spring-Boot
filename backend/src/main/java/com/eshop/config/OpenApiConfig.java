package com.eshop.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final AppProperties properties;

    @Bean
    public OpenAPI eshopOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Eshop API")
                        .version("1.0.0")
                        .description("""
                                E-commerce platform API.

                                **Localisation** - send `Accept-Language: ar` or `en`. Arabic is the default.

                                **Errors** - every failure is an RFC 9457 problem document. Branch on the
                                stable `code` member, never on `detail`, which is prose and may be reworded.

                                **Money** - always `{ "amount": "1299.00", "currency": "EGP" }`. The amount is a
                                string so it survives JavaScript's number type intact.

                                **Idempotency** - `POST /checkout/orders`, `POST /payments/intent`, and
                                `POST /admin/orders/{id}/refund` require an `Idempotency-Key` header. Replaying a
                                key with the same body returns the original response; replaying it with a
                                different body returns 409 `IDEMPOTENCY_CONFLICT`.
                                """)
                        .contact(new Contact().name("Eshop Engineering").email("engineering@eshop.eg"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url(properties.getBaseUrl()).description("Current environment"),
                        new Server().url("http://localhost:8080").description("Local")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login. Valid for 15 minutes.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /** Storefront surface - what the web and mobile clients consume. */
    @Bean
    public GroupedOpenApi storefrontApi() {
        return GroupedOpenApi.builder()
                .group("storefront")
                .pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/admin/**")
                .build();
    }

    /** Admin surface - every path here is permission-guarded and audit-logged. */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/api/v1/admin/**")
                .build();
    }
}
