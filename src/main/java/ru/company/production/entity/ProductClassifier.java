package ru.company.production.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "product_classifier",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_product_classifier_code",
                        columnNames = "code"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ProductClassifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Код вводится пользователем.
     * Допустимые значения: 1000–9999.
     */
    @Min(1000)
    @Max(9999)
    @Column(nullable = false)
    private Integer code;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    private ProductType productType;

    /*
     * Обязательно для прибора, ячейки и системы.
     * Для датчика значение всегда null.
     */
    @Column(name = "product_name", length = 255)
    private String name;

    /*
     * Заполняется только для типа SENSOR.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "sensor_catalog_id",
            foreignKey = @ForeignKey(
                    name = "fk_product_classifier_sensor_catalog"
            )
    )
    private SensorCatalog sensorCatalog;

    @Column(length = 2000)
    private String note;

    /*
     * Изделия, входящие в этот классификатор.
     */
    @OneToMany(
            mappedBy = "owner",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("position ASC")
    private List<ClassifierInclusion> inclusions = new ArrayList<>();

    @Version
    private Long version;

    public String getDisplayName() {
        if (productType == ProductType.SENSOR && sensorCatalog != null) {
            return sensorCatalog.getName();
        }

        return name;
    }

    public void addInclusion(ClassifierInclusion inclusion) {
        inclusion.setOwner(this);
        inclusions.add(inclusion);
    }

    public void clearInclusions() {
        inclusions.clear();
    }
}