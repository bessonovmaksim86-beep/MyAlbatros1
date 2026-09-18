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
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Серийный номер изделия в реестре.
 *
 * Реестр один на все типы изделий: тип хранится колонкой, а уникальный
 * ключ (product_type, serial) даёт независимую нумерацию в пределах типа.
 * Это проще четырёх отдельных таблиц: поиск и выдача номера остаются
 * одним запросом.
 *
 * Полный номер изделия = 4 цифры кода классификатора + 5 цифр серии:
 *
 *     1212 (код) + 00022 (серия) → 121200022
 *
 * В базе серия хранится числом (22), пять знаков добавляются при выводе.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "product_serials",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_product_serial_type_serial",
                columnNames = {"product_type", "serial"}
        )
)
public class ProductSerial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Тип изделия: нумерация ведётся независимо по каждому типу. */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    private ProductType productType;

    /* Порядковый номер в пределах типа: 1..99999. */
    @Column(name = "serial", nullable = false)
    private int serial;

    /* Изделие, для которого выдан номер — даёт 4-значный префикс. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "product_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /* Заказ на производство, в рамках которого выдан номер. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "issued_production_order_id",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_production_order"
            )
    )
    private ProductionOrder issuedProductionOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductSerialStatus status = ProductSerialStatus.NEW;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "note", length = 2000)
    private String note;

    /** Серия в пять знаков с дополнением нулями: 22 → «00022». */
    public String getFormattedSerial() {
        return String.format("%05d", serial);
    }

    /**
     * Полный номер изделия: код классификатора + пятизначная серия.
     * Например 1212 и 22 → «121200022».
     */
    public String getFullNumber() {
        Integer code = productClassifier == null
                ? null
                : productClassifier.getCode();

        return (code == null ? "" : code) + getFormattedSerial();
    }

    /**
     * Готовая строка списка изделий: «121200022 - Название изделия».
     * Наименование берётся из классификатора, для датчика — из
     * справочника датчиков (getDisplayName()).
     */
    public String getDisplayLabel() {
        String name = productClassifier == null
                ? null
                : productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? getFullNumber()
                : getFullNumber() + " - " + name;
    }

    /**
     * Наименование изделия без номера.
     *
     * Нужно там, где номер уже выводится отдельной колонкой (выбор
     * датчиков в заказе-комплектующем), — дублировать его в той же
     * строке незачем. Связь классификатора ленивая, поэтому значение
     * отдаётся здесь, а список для таких мест читается детальным
     * запросом с fetch join.
     */
    @Transient
    public String getDisplayName() {
        String name = productClassifier == null
                ? null
                : productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? "Изделие"
                : name;
    }

    /** Тип изделия для подписи в списках. */
    @Transient
    public String getTypeName() {
        return productType == null ? "—" : productType.getDisplayName();
    }
}
