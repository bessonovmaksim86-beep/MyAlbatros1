package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Сотрудник, способный выполнить операцию, вместе с его загрузкой.
 *
 * Мастер выбирает исполнителя не по фамилии, а по тому, есть ли у
 * человека окно в плановой date: планировщик уже занял его время
 * строками {@code CONFIRMED}, и без этих цифр распределение превращалось
 * бы в «назначь на первого сверху».
 *
 * Загрузка считается по подразделениям того дня, на который назначена
 * операция, поэтому поле {@code busyHours} относится именно к той дате,
 * под которую мастер кладёт операцию, а не к неделе в целом.
 *
 * @param id        идентификатор учётной записи
 * @param fullName  фамилия и инициалы для подписи
 * @param specialty должность из учётной записи
 * @param username  логин — подписывает сотрудника без заполненного ФИО
 * @param busyHours сколько часов у сотрудника уже занято в этот день
 * @param freeHours сколько часов остаётся в пределах смены
 * @param shiftHours смена, от которой считается остаток
 * @param absent    сотрудник в этот день отсутствует
 */
public record AssignableExecutor(
        Long id,
        String fullName,
        String specialty,
        String username,
        BigDecimal busyHours,
        BigDecimal freeHours,
        BigDecimal shiftHours,
        boolean absent
) {

    /** Подпись сотрудника: ФИО может не заполняться, тогда логин. */
    public String label() {
        if (fullName != null && !fullName.isBlank()) {
            return fullName.trim();
        }

        return username == null || username.isBlank()
                ? "сотрудник " + id
                : username;
    }

    /** Часы без хвостовых нулей: «2», а не «2.000». */
    public String busyLabel() {
        return plain(busyHours);
    }

    public String freeLabel() {
        return freeHours == null ? "—" : plain(freeHours);
    }

    /** Остаток отрицательным не бывает, но перегруз показать надо. */
    public boolean overloaded() {
        return !absent
                && freeHours != null
                && freeHours.compareTo(BigDecimal.ZERO) <= 0;
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "—";
        }

        return value.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
