package com.zwx.zwxagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ZwxAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwxAgentApplication.class, args);
    }

}
