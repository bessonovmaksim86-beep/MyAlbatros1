package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Форма заявки на отбраковку ячейки/подсборки.
 *
 * Номер — единственные входные данные об изделии, которые вводит
 * оператор. Разновидность, изделие, партию и местоположение берёт
 * сервис: {@link ru.company.production.service.PendingDefectService}
 * читает их из реестра и журнала перемещений. Передавать их формой
 * значило бы сверять с базой и отклонять при расхождении — та же
 * работа, но с доверенным источником на стороне браузера.
 *
 * Местоположение в форме отсутствует намеренно: заявка обязана
 * фиксировать тот цех или склад, где ячейка числится по журналу,
 * а не тот, который оператор посчитал нужным вписать.
 */
@Getter
@Setter
@NoArgsConstructor
public class PendingDefectForm {

    /** Полный номер ячейки/подсборки: 4 цифры кода + 5 цифр серии. */
    @NotBlank(message = "Укажите номер ячейки или подсборки")
    @Size(max = 20, message = "Номер слишком длинный")
    private String fullNumber;

    /** Причина брака — по ней представитель бюро решает, верить ли браку. */
    @NotBlank(message = "Укажите причину отбраковки")
    @Size(max = 500, message = "Причина длиннее 500 символов")
    private String defectReason;

    /** Этап, на котором выявили брак. */
    @NotBlank(message = "Укажите этап отбраковки")
    @Size(max = 100, message = "Этап слишком длинный")
    private String defectStage;
}
