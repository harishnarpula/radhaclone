package com.askoxy.radhaai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;


@SpringBootApplication
@EnableAsync
public class AskoxyRadhaiAIApplication {
    public static void main(String[] args) {
        SpringApplication.run(AskoxyRadhaiAIApplication.class, args);
    }
}
