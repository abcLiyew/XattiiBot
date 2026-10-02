package com.esdllm;

import com.esdllm.config.DbInitializer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.esdllm.mapper")
@EnableScheduling
@EnableAsync
@SpringBootApplication(exclude={DataSourceAutoConfiguration.class})
public class Main {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Main.class);
        // 建库建表必须在任何 bean 实例化之前跑完：LoadDSConfig 的 @PostConstruct 一上来就查库，
        // 而 SQLite 的文件父目录不存在时 Druid 取连接直接失败，启动就挂。
        // 为什么用监听器而不是 @PostConstruct / ApplicationRunner，见 DbInitializer 类注释。
        app.addListeners(new DbInitializer());
        app.run(args);
    }

}