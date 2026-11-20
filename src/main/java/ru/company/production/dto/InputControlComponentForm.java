package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Запись журнала входного контроля несерийных комплектующих.
 *
 * Дата контроля в форме отсутствует: её проставляет база в момент
 * внесения записи, как это делает бумажный журнал, где колонку
 * «Дата контроля» заполняют при регистрации строки. Вид контроля
 * тоже не выбирается — в этом разделе он всегда «Входной».
 *
 * Поставщик задают двумя полями: выбором из справочника
 * ({@code supplierId}) либо новым наименованием ({@code newSupplierName}),
 * если нужной записи в справочнике ещё нет. Оба поля заполнять
 * нельзя — сервис откажет, иначе строка журнала осталась бы без
 * однозначного поставщика.
 *
 * Даты читаются в привычной для бланка форме «дд.мм.гггг», а не ISO:
 * реквизиты сверяют с сопроводительным документом, где даты стоят
 * в русском формате.
 */
@Getter
@Setter
@NoArgsConstructor
public class InputControlComponentForm {

    /** Идентификатор записи: пустой — новая строка журнала. */
    private String id;

    @NotBlank(message = "Укажите наименование, марку и тип продукции")
    @Size(max = 500, message = "Наименование: не более 500 символов")
    private String productName;

    /*
     * Поставщик из справочника. Пустое значение означает «не выбран»,
     * поэтому поле читается строкой: выпадающий список отдаёт пустую
     * строку, а прямое связывание с Long прервало бы обработку запроса.
     */
    private String supplierId;

    /*
     * Новое наименование поставщика: заводится в справочник при
     * сохранении, если поле заполнено.
     */
    @Size(max = 255, message = "Новый поставщик: не более 255 символов")
    private String newSupplierName;

    @Size(max = 100, message = "Номер документа: не более 100 символов")
    private String docNumber;

    @DateTimeFormat(pattern = "dd.MM.yyyy")
    private LocalDate docDate;

    @PositiveOrZero(message = "Общее количество не может быть отрицательным")
    private Integer totalQuantity;

    @PositiveOrZero(message = "Проверенное количество не может быть отрицательным")
    private Integer checkedQuantity;

    @PositiveOrZero(message = "Забракованное количество не может быть отрицательным")
    private Integer scrapQuantity;

    @Size(max = 100, message = "Номер акта: не более 100 символов")
    private String actNumber;

    @DateTimeFormat(pattern = "dd.MM.yyyy")
    private LocalDate actDate;

    @Size(max = 1000, message = "Описание несоответствия: не более 1000 символов")
    private String description;

    @Size(max = 1000, message = "Принятые меры: не более 1000 символов")
    private String measures;

    @Size(max = 2000, message = "Примечание: не более 2000 символов")
    private String note;

    /** Идентификатор записи числом; пусто — новая запись. */
    public Long idAsLong() {
        try {
            return id == null || id.isBlank()
                    ? null
                    : Long.valueOf(id.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Поставщик из справочника числом; {@code null} — не выбран. */
    public Long supplierIdAsLong() {
        try {
            return supplierId == null || supplierId.isBlank()
                    ? null
                    : Long.valueOf(supplierId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
