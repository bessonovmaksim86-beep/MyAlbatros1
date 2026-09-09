package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "app_users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_app_user_username",
                        columnNames = "username"
                )
        }
)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "full_name", length = 255)
    private String fullName;

    /**
     * Должность пользователя.
     * Например: инженер-технолог, слесарь, контролёр.
     */
    @Column(length = 255)
    private String specialty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "service_id",
            foreignKey = @ForeignKey(
                    name = "fk_app_user_service"
            )
    )
    private ProductionService service;

    /**
     * Может быть null.
     * Например, для пользователя СГМ.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "organization_unit_id",
            foreignKey = @ForeignKey(
                    name = "fk_app_user_organization_unit"
            )
    )
    private OrganizationUnit organizationUnit;

    /**
     * Руководитель службы,
     * руководитель подразделения
     * или сотрудник службы.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "user_type",
            length = 40
    )
    private UserType userType;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "password_ciphertext", length = 1000)
    private String passwordCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}