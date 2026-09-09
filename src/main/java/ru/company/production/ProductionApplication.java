package ru.company.production;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductionApplication.class, args);
    }
}
/*
mvn flyway:repair `
        "-Dflyway.url=jdbc:mysql://localhost:3306/production_system" `
        "-Dflyway.user=root" `
        "-Dflyway.password=12345"*/
