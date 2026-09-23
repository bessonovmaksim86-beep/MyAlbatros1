package ru.company.production.dto;

import java.util.List;

/**
 * Строка календаря цеха: сотрудник и его дни за месяц.
 *
 * @param employeeId  идентификатор сотрудника — подставляется в форму
 * @param fullName    фамилия и инициалы
 * @param specialty   должность
 * @param days        ячеки месяца (всегда 31, хвост месяца пустой)
 * @param absenceDays сколько дней месяца отмечено — сводка в шапке строки
 */
public record WorkshopCalendarRow(
        Long employeeId,
        String fullName,
        String specialty,
        List<WorkshopCalendarCell> days,
        int absenceDays
) {
}