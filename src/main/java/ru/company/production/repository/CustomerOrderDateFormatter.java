package ru.company.production.repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Форматирование дат для списков заказов покупателя.
 */
public final class CustomerOrderDateFormatter {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private CustomerOrderDateFormatter() {
    }

    public static String format(LocalDate date) {
        return date == null
                ? "—"
                : date.format(FORMATTER);
    }
}