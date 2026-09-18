package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/*
 * Компонент состава системы.
 *
 * Систему образуют роды датчиков (ссылка на sensor_catalog) и приборы
 * (ссылка на product_classifier типа DEVICE). Ровно одна из ссылок
 * заполнена. Для компонента хранятся фактическое количество,
 * максимально допустимое в системе, режим включения и единица измерения.
 */
@Entity
@Table(name = "system_components")
@Getter
@Setter
@NoArgsConstructor
public class SystemComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "system_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_system_components_system"))
    private ProductClassifier system;

    /* Род датчика из каталога (null, если компонент — прибор). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_catalog_id",
            foreignKey = @ForeignKey(name = "fk_system_components_sensor_catalog"))
    private SensorCatalog sensorCatalog;

    /* Прибор-классификатор (null, если компонент — род датчика). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_classifier_id",
            foreignKey = @ForeignKey(name = "fk_system_components_classifier"))
    private ProductClassifier componentClassifier;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    /* Верхняя граница в системе; null — не ограничено. */
    @Column(name = "max_quantity", precision = 15, scale = 3)
    private BigDecimal maxQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "inclusion_mode", nullable = false, length = 20)
    private InclusionMode inclusionMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeasurementUnit unit;

    @Column(nullable = false)
    private Integer position;
}
