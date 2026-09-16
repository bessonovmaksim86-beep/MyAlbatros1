package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.GenerationType;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "organization_units",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_organization_unit_code",
                        columnNames = "code"
                ),
                @UniqueConstraint(
                        name = "uk_organization_unit_service_name",
                        columnNames = {
                                "service_id",
                                "name"
                        }
                )
        }
)
public class OrganizationUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;



    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "service_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_organization_unit_service"
            )
    )
    private ProductionService service;

    @Column(nullable = false)
    private boolean active = true;
}