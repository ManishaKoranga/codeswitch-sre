package com.codeswitch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CodeSwitchConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
