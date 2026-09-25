package org.isha.candidatesearch;

import org.isha.candidatesearch.config.CandidateSearchProperties;
import org.isha.candidatesearch.db.SchemaService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(CandidateSearchProperties.class)
public class CandidateSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(CandidateSearchApplication.class, args);
    }

    @Bean
    ApplicationRunner schemaInitializer(SchemaService schemaService) {
        return args -> schemaService.ensureTablesExist();
    }
}
