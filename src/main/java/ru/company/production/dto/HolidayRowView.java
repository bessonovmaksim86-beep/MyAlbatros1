package ru.company.production.dto;

import ru.company.production.entity.HolidayDay;

import java.time.format.DateTimeFormatter;

/**
 * Строка справочника нерабочих дней для таблицы администратора.
 *
 * Дата отдаётся подписью: форматирование через {@code #temporals}
 * потребовало бы thymeleaf-extras-java8time, которого в зависимостях нет,
 * а формат {@code дд.мм.гггг} используется в календарях всех модулей.
 *
 * @param id        идентификатор записи для удаления
 * @param dateLabel дата в формате дд.мм.гггг
 * @param kindCode  код вида: по нему строка красится в CSS
 * @param kindLabel вид дня человекочитаемым текстом
 * @param name      название дня
 */
public record HolidayRowView(
        Long id,
        String dateLabel,
        String kindCode,
        String kindLabel,
        String name
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static HolidayRowView of(HolidayDay holiday) {
        return new HolidayRowView(
                holiday.getId(),
                holiday.getHolidayDate().format(RU_DATE),
                holiday.getKind().name(),
                holiday.getKind().getDisplayName(),
                holiday.getName()
        );
    }
}
