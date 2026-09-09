package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "sensor_catalog",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_sensor_catalog_name",
                        columnNames = "name"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class SensorCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "search_parameters", length = 100)
    private String searchParameters;

    @Column(name = "protocol_search_path", length = 1000)
    private String protocolSearchPath;
}