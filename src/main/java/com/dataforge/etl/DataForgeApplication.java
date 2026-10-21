package com.dataforge.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DataForge 多 Agent ETL / 数据管道编排平台启动类。
 *
 * <p>IDEA 中直接运行本类的 main 方法即可启动整个系统:
 * 后端 API + 内嵌前端页面(http://localhost:8080)。</p>
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class DataForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataForgeApplication.class, args);
    }
}
