package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Изделие (датчик) со всеми своими операциями для цеха.
 *
 * Строка таблицы распределения: мастер видит серийный номер,
 * наименование и заказ, а в раскрытии — все операции, которые цех
 * должен выполнить над этим изделием, с сотрудниками и назначением.
 *
 * @param customerOrderId  заказ покупателя, которому принадлежит изделие
 * @param productionOrderId заказ на производство — основание строки, если
 *                          изделие планирует он, а не позиция заказа
 *                          покупателя (V56); для плана заказа пустой
 * @param itemPosition     позиция заказа
 * @param serialNumbers    серийные номера изделия без повторов
 * @param itemFullName     полное наименование изделия («как в КД»)
 * @param orderNumber      номер заказа покупателя
 * @param operations       все операции изделия для цеха
 * @param totalHours       суммарная трудоёмкость всех операций
 * @param rowCount         суммарное количество экземпляров
 * @param assignedCount    сколько операций уже распределены
 */
public record ProductOperationsView(
        Long customerOrderId,
        Long productionOrderId,
        Integer itemPosition,
        List<String> serialNumbers,
        String itemFullName,
        String orderNumber,
        List<ProductOperationCard> operations,
        BigDecimal totalHours,
        long rowCount,
        long assignedCount
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Серийники изделия одной строкой — видны без раскрытия панели.
     */
    public String serialsLabel() {
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            return null;
        }

        String joined = String.join(", ",
                serialNumbers.stream().limit(4).toList());

        return serialNumbers.size() > 4
                ? joined + " … всего " + serialNumbers.size()
                : joined;
    }

    /** Полное наименование изделия одной строкой. */
    public String itemNameLabel() {
        return itemFullName == null || itemFullName.isBlank()
                ? "—"
                : itemFullName.trim();
    }

    /** Суммарная трудоёмкость одной строкой: «28 ч.». */
    public String totalHoursLabel() {
        if (totalHours == null) {
            return "—";
        }

        return totalHours.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Сколько операций в группе. */
    public long operationCount() {
        return operations == null ? 0L : operations.size();
    }

    /**
     * Исполнители изделия одной строкой.
     *
     * Изделие — не одна работа, поэтому закреплённых людей может быть
     * несколько: каждый свою операцию. Повторы отбрасываются — десять
     * экземпляров одной операции, ушедших одному сотруднику, обязаны
     * дать одну фамилию, а не десять одинаковых.
     */
    public String executorsLabel() {
        if (operations == null) {
            return null;
        }

        List<String> names = new ArrayList<>();

        for (ProductOperationCard op : operations) {
            String name = op.executorLabel();

            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name.trim());
            }
        }

        if (names.isEmpty()) {
            return null;
        }

        return names.size() > 2
                ? String.join(", ", names.stream().limit(2).toList())
                        + " … всего " + names.size()
                : String.join(", ", names);
    }

    /**
     * Есть ли хотя бы одна нераспределённая операция.
     *
     * Статус строки описывает распределение, а не пригодность людей:
     * операция считается нераспределённой, пока за неё не закреплён
     * исполнитель (флаг {@code assigned} карточки). Если все операции
     * закреплены, строка полностью распределена.
     */
    public boolean hasUnassigned() {
        if (operations == null) {
            return false;
        }

        for (ProductOperationCard op : operations) {
            if (!op.assigned()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Подпись статуса для бейджа строки.
     *
     * Считается по флагу {@code assigned} карточек: ни одна не
     * закреплена — «распределить», все закреплены — «распределено»,
     * и то и другое — «частично». Нехватка способных сотрудников на
     * статус не влияет: это отдельное предупреждение внутри карточки.
     */
    public String statusBadge() {
        if (operations == null || operations.isEmpty()) {
            return "нет операций";
        }

        long assignedOps = operations.stream()
                .filter(ProductOperationCard::assigned)
                .count();

        if (assignedOps == 0) {
            return "распределить";
        }

        return assignedOps == operations.size()
                ? "распределено"
                : "частично";
    }
}
