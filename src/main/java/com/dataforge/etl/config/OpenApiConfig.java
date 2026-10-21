package com.dataforge.etl.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 配置,访问地址:http://localhost:8080/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dataforgeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DataForge 多 Agent ETL / 数据管道编排平台 API")
                        .description("多 Agent ETL 管道编排、执行监控、数据质量与告警的一站式平台")
                        .version("1.0.0")
                        .contact(new Contact().name("DataForge Team")));
    }
}
