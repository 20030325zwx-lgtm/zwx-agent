package com.zwx.aster.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 评测中台独立服务（端口 8140）：数据集/实验/结果/进度。 */
@SpringBootApplication
@EnableScheduling
public class EvalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalServiceApplication.class, args);
    }
}
