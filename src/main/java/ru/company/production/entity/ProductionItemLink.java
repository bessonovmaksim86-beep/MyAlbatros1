package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Связь между двумя конкретными экземплярами изделий.
 *
 * Строка описывает, что один серийник установлен в другой:
 * ячейка собрана с прибором, в ячейку поставлен датчик,
 * в систему входят датчики, приборы или ячейки.
 *
 * Обе стороны — записи реестра {@link ProductSerial}, вид связи
 * ограничен парами типов изделий (см. {@link ItemLinkType}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "production_item_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_production_item_link",
                columnNames = {"parent_serial_id", "child_serial_id", "link_type"}
        )
)
public class ProductionItemLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Изделие, в которое устанавливают: ячейка или система. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "parent_serial_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_production_item_link_parent"
            )
    )
    private ProductSerial parentSerial;

    /* Устанавливаемое изделие: прибор или датчик. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "child_serial_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_production_item_link_child"
            )
    )
    private ProductSerial childSerial;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 30)
    private ItemLinkType linkType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "note", length = 2000)
    private String note;
}
