package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Поставщик, чьи полуфабрикаты приходят на склад.
 *
 * Справочник хранит написание имени: в партии прихода поставщик
 * записан свободной строкой (cell_receiving_batches.supplier_name),
 * и без фиксированного написания один поставщик заводится в
 * нескольких вариантах, из-за чего сводка по нему рассыпается.
 *
 * Ссылки из партий на справочник нет намеренно: приходы оформляются
 * с входящих документов, и связывать их с заранее заведённой
 * записью означало бы обязать склад вести справочник до первого
 * прихода. Поэтому справочник подсказывает написание в форме
 * прихода, а статистика считается по строкам партий.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "warehouse_suppliers")
public class WarehouseSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "note", length = 1000)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_warehouse_suppliers_author"
            )
    )
    private AppUser createdBy;
}
