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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Запись журнала входного контроля несерийных комплектующих.
 *
 * Повторяет структуру бумажного журнала входного контроля (лист
 * «Журнал с 06.26»), но без полей, которые на картинке отмечены
 * как «не нужно» (контролируемая операция) и «автоматическое»
 * (дата контроля).
 *
 * control_type зафиксирован на уровне БД и сущности — всегда
 * «ВХОДНОЙ», поэтому отдельного поля выбора в форме нет:
 * журнал не смешивает виды, как это сделано в бумажном бланке.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "input_control_components_journal")
public class InputControlComponent {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Дата контроля: при ручном внесении проставляется автоматически
     * в момент сохранения (см. @PrePersist normalize), при импорте
     * берётся из колонки «Дата контроля» листа.
     *
     * Поле updatable=false: дата задаёт место строки в журнале и
     * сдвигать её при правке нельзя. @CreationTimestamp не используем —
     * он перезаписывал бы дату импорта текущим моментом, поэтому
     * значение ставит сервис, а сущность только бережёт от правки.
     */
    @Column(name = "control_date", nullable = false, updatable = false)
    private LocalDateTime controlDate;

    /*
     * Вид контроля: в этом журнале всегда «Входной», поэтому поле
     * не вынесено в форму. Значение хранится, а не вычисляется:
     * бумажный журнал содержит и «Операционный», и перечню позже
     * может понадобиться различать строки по этому признаку.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", nullable = false, length = 20)
    private JournalControlType controlType = JournalControlType.INPUT;

    /** Наименование, марка, тип комплектующего — свободный текст. */
    @Column(name = "product_name", nullable = false, length = 500)
    private String productName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "supplier_id",
            foreignKey = @ForeignKey(
                    name = "fk_journal_supplier"
            )
    )
    private WarehouseSupplier supplier;

    /**
     * Копия имени поставщика для записей без привязки к справочнику.
     *
     * Если поставщик не выбран из справочника, имя сохраняется
     * в supplierName; если позже его заведут в справочник —
     * можно привязать.
     */
    @Column(name = "supplier_name", length = 255)
    private String supplierName;

    @Column(name = "doc_number", length = 100)
    private String docNumber;

    @Column(name = "doc_date")
    private LocalDate docDate;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "checked_quantity")
    private Integer checkedQuantity;

    @Column(name = "scrap_quantity")
    private Integer scrapQuantity;

    @Column(name = "act_number", length = 100)
    private String actNumber;

    @Column(name = "act_date")
    private LocalDate actDate;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "measures", length = 1000)
    private String measures;

    @Column(name = "note", length = 2000)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "checked_by",
            foreignKey = @ForeignKey(
                    name = "fk_journal_checker"
            )
    )
    private AppUser checkedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void normalize() {
        /*
         * Ручное внесение: дату контроля проставляет база в момент
         * сохранения. Импорт передаёт её строкой листа — тогда поле
         * уже заполнено и его не трогают.
         */
        if (controlDate == null) {
            controlDate = LocalDateTime.now();
        }

        productName = trimToNull(productName);

        /*
         * Справочник важнее свободной строки: привязанная запись
         * обязана нести то же написание, что и справочник, иначе
         * отбор по поставщику разойдётся с его каноническим именем.
         */
        if (supplier != null) {
            supplierName = trimToNull(supplier.getName());
        } else {
            supplierName = trimToNull(supplierName);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Форматированная дата контроля. */
    public String getFormattedControlDate() {
        return controlDate != null
                ? controlDate.format(DATE_FORMATTER)
                : "—";
    }

    /** Форматированная дата документа. */
    public String getFormattedDocDate() {
        return format(docDate);
    }

    /** Форматированная дата акта. */
    public String getFormattedActDate() {
        return format(actDate);
    }

    /** Фамилия контролёра. */
    public String getCheckerName() {
        if (checkedBy == null) {
            return "—";
        }
        String fullName = checkedBy.getFullName();
        return fullName != null && !fullName.isBlank()
                ? fullName
                : checkedBy.getUsername();
    }

    /** Имя поставщика для вывода. */
    public String getSupplierLabel() {
        if (supplier != null) {
            return supplier.getName();
        }
        return supplierName != null && !supplierName.isBlank()
                ? supplierName
                : "—";
    }

    private static String format(LocalDate date) {
        return date != null ? date.format(DATE_FORMATTER) : "—";
    }
}
