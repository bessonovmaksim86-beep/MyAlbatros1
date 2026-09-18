package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Покупатель — справочник контрагентов.
 *
 * Используется заказами покупателя (customer_orders).
 */
@Entity
@Table(
        name = "customers",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_customer_code",
                        columnNames = "code"
                ),
                @UniqueConstraint(
                        name = "uk_customer_name",
                        columnNames = "name"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "contact_person", length = 255)
    private String contactPerson;

    @Column(length = 100)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(length = 2000)
    private String note;

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
