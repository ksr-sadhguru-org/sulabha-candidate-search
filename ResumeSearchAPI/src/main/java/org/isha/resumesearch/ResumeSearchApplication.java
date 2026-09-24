package org.isha.resumesearch;

import org.isha.resumesearch.config.ResumeSearchProperties;
import org.isha.resumesearch.db.SchemaService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(ResumeSearchProperties.class)
public class ResumeSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeSearchApplication.class, args);
    }

    /** Auto-creates the schema on startup, mirroring the original service's @app.on_event("startup"). */
    @Bean
    ApplicationRunner schemaInitializer(SchemaService schemaService) {
        return args -> schemaService.ensureTablesExist();
    }
}
