package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Загрузка подразделения по одной неделе.
 */
public record CapacityWeekRow(
        int weekNumber,
        LocalDate weekFrom,
        LocalDate weekTo,
        BigDecimal capacityHours,
        BigDecimal confirmedHours,
        BigDecimal draftHours,
        BigDecimal loadHours,
        double loadPercent,
        double confirmedPercent,
        double freeHours,
        int criticalDays,
        List<CapacityDayRow> days
) {

    private static final String[] DAY_NAMES = {
            "пн", "вт", "ср", "чт", "пт", "сб", "вс"
    };

    public boolean isCritical() {
        return loadPercent > 100 || criticalDays > 0;
    }

    public boolean isWarning() {
        return loadPercent >= 85 && loadPercent <= 100;
    }

    /** Подпись «пн 12.05: 95% · вт 13.05: 110%…» для всплывающей подсказки. */
    public String dayTooltip() {
        if (days == null || days.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (CapacityDayRow day : days) {
            if (day.isHoliday()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append('\n');
            }

            builder.append(DAY_NAMES[day.dayOfWeek() - 1])
                    .append(' ')
                    .append(day.date().getDayOfMonth())
                    .append('.')
                    .append(String.format("%02d", day.date().getMonthValue()))
                    .append(": ")
                    .append(Math.round(day.loadPercent()))
                    .append('%');
        }

        return builder.toString();
    }
}
