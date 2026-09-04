package com.moveinsync.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agentic Intelligence & Reporting Layer for Enterprise Mobility.
 *
 * Phase 1 scope: a running Spring Boot service wired to PostgreSQL, exposing
 * a health endpoint that proves the Angular -> backend -> Postgres round trip
 * works end to end. Business logic (canonical schema, benchmarking,
 * reasoning, the agent orchestrator) is layered on in later phases.
 */
@SpringBootApplication
@EnableScheduling // pipeline triggers (Phase 5) will use @Scheduled
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
