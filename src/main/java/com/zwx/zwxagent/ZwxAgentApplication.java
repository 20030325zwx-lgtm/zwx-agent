package com.zwx.zwxagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ZwxAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwxAgentApplication.class, args);
    }

}
