package ru.company.production.entity;

import jakarta.persistence.*;

@Entity
@Table(
        name = "user_roles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_role_code",
                        columnNames = "code"
                ),
                @UniqueConstraint(
                        name = "uk_user_role_display_name",
                        columnNames = "display_name"
                )
        }
)
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "code",
            nullable = false,
            length = 50
    )
    private String code;

    @Column(
            name = "display_name",
            nullable = false,
            length = 255
    )
    private String displayName;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}