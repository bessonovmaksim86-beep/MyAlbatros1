package ru.company.production.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Форма отметки отсутствия в производственном календаре.
 *
 * Отметку ставят сразу на диапазон дат и сразу на нескольких
 * сотрудников: праздничные и нерабочие дни задают на весь цех, и
 * перечислять людей по одному для этого слишком долго.
 */
@Getter
@Setter
@NoArgsConstructor
public class AbsenceForm {

    /*
     * Идентификаторы держатся строками: их возвращают браузеры из
     * множественного select, и при возврате формы после ошибки те же
     * строки сверяются с значениями опций в шаблоне.
     */
    private List<String> employeeIds = new ArrayList<>();

    private LocalDate fromDate;

    private LocalDate toDate;

    /** VACATION, SICK, UNPAID или DAY_OFF. */
    private String absenceType;

    private String note;

    /*
     * Отметка другого вида поверх уже стоящей возможна только после
     * подтверждения: без него сервис отклоняет сохранение и просит
     * пользователя согласиться с перезаписью.
     */
    private boolean replaceExisting;
}