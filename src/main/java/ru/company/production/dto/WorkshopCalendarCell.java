package ru.company.production.dto;

/**
 * День месяца в календаре цеха.
 *
 * Форматирование даты, признак выходного и подпись ячейки считаются
 * на стороне приложения: в проекте не подключён диалект
 * thymeleaf-extras-java8time, поэтому {@code #temporals} в шаблонах
 * недоступен, а локальная логика в шаблоне из этих же дат и так бы
 * не выросла.
 *
 * @param day      номер числа (0 — ячейка после конца месяца)
 * @param date     дата в формате дд.мм.гггг для форм отметки
 * @param today    текущий день — выделяется в таблице
 * @param weekend  суббота или воскресенье
 * @param cssClass модификатор цвета отсутствия, пусто — сотрудник работает
 * @param mark     короткая подпись вида отсутствия в ячейке
 * @param title    всплывающая подсказка с видом отсутствия и примечанием
 */
public record WorkshopCalendarCell(
        int day,
        String date,
        boolean today,
        boolean weekend,
        String cssClass,
        String mark,
        String title
) {

    public boolean empty() {
        return day == 0;
    }

    public boolean marked() {
        return cssClass != null && !cssClass.isBlank();
    }

    /**
     * CSS-класс ячейки целиком: здесь же модификаторы текущего дня
     * и выходного, чтобы шаблон не склеивал строки.
     */
    public String cellClass() {
        StringBuilder classes = new StringBuilder();

        if (empty()) {
            classes.append(" is-empty");
        }

        if (today) {
            classes.append(" is-today");
        }

        if (weekend) {
            classes.append(" is-weekend");
        }

        if (marked()) {
            classes.append(' ').append(cssClass);
        }

        return classes.toString().trim();
    }
}
