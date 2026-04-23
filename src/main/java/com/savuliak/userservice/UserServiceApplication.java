package com.savuliak.userservice;

import com.savuliak.userservice.config.AppProperties;
import com.savuliak.userservice.config.CorsProperties;
import com.savuliak.userservice.config.InternalApiProperties;
import com.savuliak.userservice.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        JwtProperties.class,
        AppProperties.class,
        InternalApiProperties.class,
        CorsProperties.class
})
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
