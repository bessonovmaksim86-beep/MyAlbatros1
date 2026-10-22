package ru.company.production.dto;

import ru.company.production.entity.QuarantineCellKind;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Фильтр реестра наличия склада полуфабрикатов.
 *
 * Вид изделия, его наименование, контрагент и интервал дат поступления
 * — четыре условия, заданные пользователем. Пустое значение означает
 * «все»: экран открывают без фильтра, условия добавляют по мере
 * надобности (тот же приём, что у {@link QuarantineFilter}).
 *
 * Сортировка держится рядом с отбором, а не отдельным параметром
 * контроллера: перечень читают в выбранном порядке сразу после
 * отбора, и «по контрагенту» без фильтра по контрагенту — тот же
 * выбор, который человек делает одним движением.
 *
 * Границы интервала — даты, а не моменты: поле ввода даёт дату, а
 * склад мыслит днями. Моменты для запроса считает сам фильтр
 * ({@link #fromMoment()} / {@link #toMoment()}), чтобы последний день
 * интервала входил в него целиком — иначе позиция, поступившая
 * 22.10 в 18:00, пропала бы из интервала «по 22.10 включительно».
 */
public record WarehouseStockFilter(
        QuarantineCellKind kind,
        Long classifierId,
        String supplierKey,
        LocalDate dateFrom,
        LocalDate dateTo,
        Sorting sorting
) {

    /** Порядок строк реестра. */
    public enum Sorting {

        NUMBER("По номеру"),
        PRODUCT("По изделию"),
        SUPPLIER("По контрагенту"),
        RECEIVED("По поступлению");

        private final String displayName;

        Sorting(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /** Значение для адресной строки и для поля формы. */
        public String param() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Порядок по параметру адреса: неизвестное или пустое значение
         * означает порядок по умолчанию, а не ошибку — ссылку с устаревшим
         * параметром открывают без уведомления об ошибке.
         */
        public static Sorting ofNullable(String value) {
            if (value == null || value.isBlank()) {
                return NUMBER;
            }

            for (Sorting sorting : values()) {
                if (sorting.param().equalsIgnoreCase(value.trim())) {
                    return sorting;
                }
            }

            return NUMBER;
        }
    }

    /** Реестр открывают без условий, в порядке номеров. */
    public static WarehouseStockFilter empty() {
        return new WarehouseStockFilter(null, null, null, null, null, Sorting.NUMBER);
    }

    /** Задано ли хотя бы одно условие — по этому признаку рисуют «сбросить». */
    public boolean isFiltered() {
        return kind != null
                || classifierId != null
                || supplierKey != null
                || dateFrom != null
                || dateTo != null;
    }

    /** Начало интервала включительно; {@code null} — нижняя граница не задана. */
    public LocalDateTime fromMoment() {
        return dateFrom == null ? null : dateFrom.atStartOfDay();
    }

    /**
     * Конец интервала — начало следующих суток.
     *
     * Условие в запросе строится как «&lt; :to», поэтому интервал
     * «по 22.10» захватывает весь 22 октября, включая вечерние проводки.
     */
    public LocalDateTime toMoment() {
        return dateTo == null ? null : dateTo.plusDays(1).atStartOfDay();
    }

    /** Интервал задан хоть одной границей. */
    public boolean hasDateBounds() {
        return dateFrom != null || dateTo != null;
    }

    /** Написание контрагента для сравнения: то же, что хранит запрос. */
    public String normalizedSupplierKey() {
        return supplierKey == null || supplierKey.isBlank()
                ? null
                : supplierKey.trim().toLowerCase(Locale.forLanguageTag("ru"));
    }

    /** Порядок строк: незаданный означает порядок по умолчанию. */
    public Sorting sortingOrDefault() {
        return sorting == null ? Sorting.NUMBER : sorting;
    }

    /** Значения полей формы — чтобы отфильтрованный экран возвращал свой выбор. */
    public String kindParam() {
        return kind == null ? "" : kind.name();
    }

    public String dateFromParam() {
        return dateFrom == null ? "" : dateFrom.toString();
    }

    public String dateToParam() {
        return dateTo == null ? "" : dateTo.toString();
    }
}
