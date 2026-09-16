package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.GenerationType;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "production_services",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_production_service_code",
                        columnNames = "code"
                ),
                @UniqueConstraint(
                        name = "uk_production_service_name",
                        columnNames = "name"
                )
        }
)
public class ProductionService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(
            mappedBy = "service",
            fetch = FetchType.LAZY
    )
    private List<OrganizationUnit> organizationUnits =
            new ArrayList<>();
}