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

    /*
     * Стартовый номер серии (миграция V86).
     *
     * Серийные номера в пред-БД уже были выданы, и новая БД не должна
     * их задваивать. Ряд у каждого классификатора свой, поэтому админ
     * задаёт стартовый номер отдельно для каждого изделия — по умолчанию
     * с 1, если ничего не введено.
     *
     * Значение задаёт НИЖНЮЮ границу ряда: выдача считается как
     * max(startSerial, занятый максимум + 1), поэтому установленный
     * задним числом стартовый номер не может вернуть номер, который уже
     * лежит в реестре.
     *
     * У датчиков (SENSOR) область нумерации — род (sensor_catalog), а не
     * классификатор (V27), поэтому для SENSOR берётся максимальный
     * стартовый номер среди классификаторов этого рода.
     */
    @Column(name = "start_serial")
    @Min(1)
    @Max(99999)
    private Integer startSerial;

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

    /*
     * Состав системы: роды датчиков и приборы.
     * Используется только для типа SYSTEM.
     */
    @OneToMany(
            mappedBy = "system",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("position ASC")
    private List<SystemComponent> systemComponents = new ArrayList<>();

    @Version
    private Long version;

    public String getDisplayName() {
        if (productType == ProductType.SENSOR && sensorCatalog != null) {
            return sensorCatalog.getName();
        }

        return name;
    }

    /**
     * Серия, с которой ряд обязан начинаться (миграция V86).
     *
     * Пустое значение означает «с единицы» — прежнее поведение
     * нумерации, поэтому записи, где стартовый номер не задавали,
     * читаются как 1 и выдача номера для них не меняется.
     *
     * Метод, а не поле: правило «пусто → 1» повторяют три места выдачи
     * номера (резерв позиции, выпуск заказа, приход склада), и держать
     * его в каждом значило бы разойтись при первом же изменении.
     */
    @Transient
    public int getStartSerialOrOne() {
        return startSerial == null || startSerial < 1 ? 1 : startSerial;
    }

    /*
     * Наименование для списков классификатора: к названию датчика
     * через пробел добавляется строка product_name.
     *
     * Отдельный метод, а не изменение getDisplayName(): то читается
     * этикетками, складом, планом производства и матчингом датчиков,
     * где product_name сравнивается с наименованием каталога —
     * дописка строки в базовое наименование исказила бы эти данные.
     *
     * Для прибора, ячейки и системы product_name и есть наименование,
     * поэтому там дописывать нечего.
     */
    @Transient
    public String getFullDisplayName() {
        String display = trimToNull(getDisplayName());
        String extra = productType == ProductType.SENSOR
                ? trimToNull(name)
                : null;

        if (extra == null) {
            return display;
        }

        if (display == null) {
            return extra;
        }

        /*
         * Строка product_name могла уже попасть в наименование
         * каталога: дублировать её в списке не нужно.
         */
        if (display.contains(extra)) {
            return display;
        }

        return display + " " + extra;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    public void addInclusion(ClassifierInclusion inclusion) {
        inclusion.setOwner(this);
        inclusions.add(inclusion);
    }

    public void clearInclusions() {
        inclusions.clear();
    }

    public void addSystemComponent(SystemComponent component) {
        component.setSystem(this);
        systemComponents.add(component);
    }

    public void clearSystemComponents() {
        systemComponents.clear();
    }
}
