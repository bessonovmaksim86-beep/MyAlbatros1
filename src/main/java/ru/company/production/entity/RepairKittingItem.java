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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Ячейка, взятая в ремонтный заказ-комплектующий при его комплектовании.
 *
 * Состав проводится построчно, а не отметкой на заказе: ремонтный ЗНП
 * описан изделием и количеством (у ремонтных видов серийников нет,
 * см. {@link ZnpKind#isSerialBased()}), и без перечня фактически взятых
 * номеров «Готово X из Y» пришлось бы выводить из чужих документов, а
 * спор о составе остался бы неразрешимым.
 *
 * Ячейка держится полным номером, а не ссылкой на {@link ProductSerial}
 * — по тому же мотиву, что строка заказа на ремонт (V34): в изолятор
 * брака попадают и номера партий прихода, ещё не внесённые в реестр, и
 * ссылка на реестр не покрывала бы половину состава изолятора.
 *
 * Разновидность ({@link #cellKind}) копируется на момент проведения:
 * стадия движения со временем меняется (подсборка после приёмки
 * складом становится ячейкой, см. {@link QuarantineCellKind}), а
 * требование «тип ячеек соответствует виду заказа» проверяется по тому,
 * что было на момент комплектования.
 *
 * Соответствует таблице repair_kitting_items (миграция V52).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "repair_kitting_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repair_kitting_item_number",
                columnNames = {"production_order_id", "full_number"}
        )
)
public class RepairKittingItem {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Ремонтный ЗНП, по которому проведено комплектование. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "production_order_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_repair_kitting_item_order"
            )
    )
    private ProductionOrder productionOrder;

    /* Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    /* SUBASSEMBLY, CELL — разновидность на момент проведения. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", nullable = false, length = 20)
    private QuarantineCellKind cellKind;

    /*
     * Классификатор ячейки — наименование для отчёта.
     * NULL допустим: группу брака заводят и без привязки к
     * классификатору (номер вводят вручную).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_repair_kitting_item_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /* Кто и когда взял ячейку в заказ. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "kitted_by",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_repair_kitting_item_user"
            )
    )
    private AppUser kittedBy;

    @Column(name = "kitted_at", nullable = false)
    private LocalDateTime kittedAt;

    @Column(name = "note", length = 500)
    private String note;

    /*
     * Подписи готовятся здесь, а не в шаблоне: связи lazy, а
     * open-in-view выключен — обращение шаблона к kittedBy или
     * productClassifier упёрлось бы в ленивую загрузку вне сессии.
     */
    @Transient
    public String getKittedByLabel() {
        return kittedBy == null || kittedBy.getFullName() == null
                ? "—"
                : kittedBy.getFullName();
    }

    @Transient
    public String getKittedAtLabel() {
        return kittedAt == null
                ? "—"
                : kittedAt.format(DATE_TIME_FORMATTER);
    }

    @Transient
    public String getCellKindLabel() {
        return cellKind == null ? "—" : cellKind.getDisplayName();
    }

    @Transient
    public String getClassifierLabel() {
        if (productClassifier == null) {
            return "—";
        }

        String name = productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? String.valueOf(productClassifier.getCode())
                : productClassifier.getCode() + " — " + name;
    }
}
