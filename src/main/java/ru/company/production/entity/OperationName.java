package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "operation_names")
@Getter
@Setter
@NoArgsConstructor
public class OperationName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "code",
            nullable = false,
            unique = true,
            length = 50
    )
    private String code;

    @Column(
            name = "name",
            nullable = false,
            unique = true,
            length = 255
    )
    private String name;

    @Column(
            name = "active",
            nullable = false
    )
    private boolean active = true;
}