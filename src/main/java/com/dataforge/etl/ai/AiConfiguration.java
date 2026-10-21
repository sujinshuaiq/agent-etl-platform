package com.dataforge.etl.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模块配置装配:启用 AiProperties 绑定。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
}
