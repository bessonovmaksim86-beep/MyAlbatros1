package ru.company.production.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Форма добавления нерабочего дня в справочник администратора.
 *
 * День заводится один раз на всё предприятие: отметки всем действующим
 * сотрудникам создаёт сервис, поэтому список сотрудников в форме
 * отсутствует намеренно.
 */
@Getter
@Setter
@NoArgsConstructor
public class HolidayForm {

    private LocalDate holidayDate;

    /** HOLIDAY или DAY_OFF — строкой, как и в остальных формах системы. */
    private String kind;

    /** Название дня: «День защитника Отечества», «Перенос с 7 января». */
    private String name;

    /*
     * Перезапись нужна, когда на дату уже стоит отпуск или больничный:
     * без явного согласия сервис отклоняет сохранение, иначе чужое
     * отсутствие пропало бы молча.
     */
    private boolean replaceExisting;
}
