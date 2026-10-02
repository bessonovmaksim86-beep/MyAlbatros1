package ru.company.production.repository;

/**
 * Наименование изделия позиции заказа покупателя.
 *
 * Нужно разделу распределения: главная строка таблицы подписывается
 * изделием, а не операцией, и наименование обязано быть видно даже
 * тогда, когда выпуск ещё не создан и серийного номера нет. У самой
 * строки графика (V43) наименования нет — она хранит только пару
 * «заказ + позиция», поэтому подпись берётся из позиции заказа.
 *
 * Приоритет наименования собирает {@link #getDisplayName()}: полное
 * обозначение «как в КД», введённое диспетчером, а при его отсутствии —
 * род датчика либо наименование классификатора. Тот же порядок, что в
 * {@link ProductionOrderSerialProjection}, чтобы строка таблицы не
 * меняла подпись в момент выпуска изделия.
 */
public interface OrderItemNameView {

    Long getCustomerOrderId();

    Integer getItemPosition();

    String getFullName();

    String getSensorName();

    String getClassifierName();

    default String getDisplayName() {
        String full = pick(getFullName());
        if (full != null) {
            return full;
        }

        String sensor = pick(getSensorName());
        if (sensor != null) {
            return sensor;
        }

        return pick(getClassifierName());
    }

    private static String pick(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
