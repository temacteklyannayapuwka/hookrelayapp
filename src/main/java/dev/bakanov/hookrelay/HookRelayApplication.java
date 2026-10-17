package dev.bakanov.hookrelay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HookRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(HookRelayApplication.class, args);
    }
}
