package ru.company.production.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Данные прихода ячеек на склад полуфабрикатов.
 *
 * Поля обязательны, кроме примечания: партия без поставщика или
 * приходного ордера не подтверждается, потому что именно эти
 * реквизиты оператор сверяет перед окончательным оприходованием.
 *
 * Классификатор выбирается из списка ячеек — вручную его код не
 * вводится: из кода складывается префикс серийных номеров, и ошибка
 * в одной цифре дала бы номера чужого изделия.
 */
@Getter
@Setter
@NoArgsConstructor
public class CellReceivingForm {

    @NotNull(message = "Выберите поставщика из списка")
    private Long supplierId;

    @NotBlank(message = "Укажите номер приходного ордера")
    @Size(max = 100, message = "Номер ордера: не более 100 символов")
    private String incomingOrderNumber;

    @NotBlank(message = "Укажите номер изменения")
    @Size(max = 50, message = "Номер изменения: не более 50 символов")
    private String changeNumber;

    @NotNull(message = "Укажите количество поступивших ячеек")
    @Min(value = 1, message = "Количество: не меньше 1 ячейки")
    @Max(value = 500, message = "Количество: не больше 500 ячеек одной партией")
    private Integer quantity;

    @NotNull(message = "Выберите название ячейки из списка")
    private Long cellClassifierId;

    @Size(max = 2000, message = "Примечание: не более 2000 символов")
    private String note;
}
