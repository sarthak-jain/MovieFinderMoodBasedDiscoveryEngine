package com.moviefinder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MovieFinderApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieFinderApplication.class, args);
    }
}
