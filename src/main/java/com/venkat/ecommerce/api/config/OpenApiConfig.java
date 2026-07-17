package com.venkat.ecommerce.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ecommerceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ecommerce API")
                        .description("REST API for managing categories, products, customers, orders and payments.")
                        .version("v1")
                        .contact(new Contact().name("Venkat").email("meetme.venkat@gmail.com"))
                        .license(new License().name("Apache 2.0")));
    }
}
