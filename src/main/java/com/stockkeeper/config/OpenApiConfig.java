package com.stockkeeper.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger UI configuration.
 *
 * Swagger UI:  http://localhost:8080/swagger-ui/index.html
 * OpenAPI JSON: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockKeeperOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("StockKeeper API")
                        .description("""
                                Capacity reservation and stock management service.

                                **Command endpoints** (POST) are idempotent state-transition operations \
                                that move capacity between buckets (available, held, committed, loaded) \
                                using DynamoDB TransactWriteItems. Idempotency is enforced by a \
                                deterministic reservation_id derived from business fields — there are \
                                no client-provided idempotency keys.

                                **Query endpoints** (GET) provide read-only access to CapacityStock items.

                                See [/docs/sample-request.md] in the repository for full JSON examples.""")
                        .version("1.0.0")
                        .contact(new Contact().name("StockKeeper")))
                .tags(List.of(
                        new Tag().name("Stock Commands")
                                .description("State-transition operations (hold, commit, load, release). "
                                        + "These are command-style POST endpoints — NOT simple CRUD. "
                                        + "Each operation is naturally idempotent: retrying the same "
                                        + "logical request returns 200 with idempotent=true."),
                        new Tag().name("Stock Queries")
                                .description("Read-only endpoints for listing and looking up "
                                        + "CapacityStock items.")
                ));
    }
}
