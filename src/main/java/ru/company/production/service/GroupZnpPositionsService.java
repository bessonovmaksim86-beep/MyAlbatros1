package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.GroupZnpPositions;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.CustomerOrderDateFormatter;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.PendingDefectRepository;
import ru.company.production.repository.ProductionOrderListItemView;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Колонка «Где позиции» в списке групповых ЗНП.
 *
 * Заказ в работе держит группу ячеек, и диспетчеру важно видеть не
 * маршрут целиком, а работу, на которой эти ячейки стоят: по ней видно,
 * какой цех держит заказ и почему он не движется.
 *
 * Три факта складываются здесь, а не в шаблоне:
 *
 *   * незакрытые строки плана заказа (V56) — где работа ещё не
 *       проведена; первой строкой экземпляра считается его текущая
 *       работа, тот же разбор, что в {@code GroupZnpSerialRow#current};
 *   * ячейки, внесённые складом в заказ (матрица комплектования, V20) —
 *       позиция как физическая вещь; i-я по внесению ячейка
 *       принадлежит экземпляру с номером i, правило введено
 *       SubassemblyKittingService#completeScheduleRow;
 *   * заявки на отбраковку, ожидающие решения бюро, — позицию в браке
 *       цех уже снял с работы, и показывать её как очередь нельзя.
 *
 * Экземпляр без внесённой ячейки в расчёт не входит: работа планом
 * заявлена, но позиции у неё ещё нет, и «где позиции» про такой
 * экземпляр отвечает честно — нигде.
 *
 * Один пакетный запрос на каждый факт вместо запроса на строку:
 * список показывает десятки заказов.
 */
@Service
@RequiredArgsConstructor
public class GroupZnpPositionsService {

    private final OrderScheduleRowRepository scheduleRowRepository;
    private final SerialProductionOrderRepository serialProductionOrderRepository;
    private final PendingDefectRepository pendingDefectRepository;

    /**
     * Сводка по строкам списка: идентификатор заказа → его позиции.
     *
     * Считаются только запущенные заказы: у «Нового» план ещё не
     * построен, а у закрытого или отменённого позиций не осталось, —
     * для них колонка обязана остаться прочерком.
     */
    @Transactional(readOnly = true)
    public Map<Long, GroupZnpPositions> ofListItems(
            List<ProductionOrderListItemView> rows) {

        List<Long> orderIds = runningIds(rows);

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<GroupZnpPositions.Work>> workOfInstance = openWorkByInstance(orderIds);
        Map<Long, List<String>> numbersByOrder = attachedNumbers(orderIds);
        Set<String> defective = pendingDefectRepository
                .findAwaitingDecisionFullNumbers();

        Map<Long, GroupZnpPositions> result = new LinkedHashMap<>();

        for (Long orderId : orderIds) {

            GroupZnpPositions positions = summarize(
                    workOfInstance.getOrDefault(orderId, List.of()),
                    numbersByOrder.getOrDefault(orderId, List.of()),
                    defective
            );

            if (!positions.isEmpty()) {
                result.put(orderId, positions);
            }
        }

        return result;
    }

    /**
     * Срок выполнения заказа по его плану: «идентификатор заказа →
     * дата в формате дд.мм.гггг».
     *
     * Колонка «Срок» списка обязана показывать срок, по которому заказ
     * ведут, а не пустую клетку: групповой заказ заводят без заказа
     * покупателя, и срока извне у него нет. План (V56) считает дату
     * окончания работ, и она точнее любой записи в карточке — тот же
     * разбор, что в SubassemblyKittingService#planDueDate.
     *
     * Заказа в выдаче нет, если плана у него ещё нет: тогда колонка
     * остаётся на сроке карточки (или на прочерке), и сервис не
     * выдумывает дату.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> planDueDates(
            List<ProductionOrderListItemView> rows) {

        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        List<Long> orderIds = rows.stream()
                .map(ProductionOrderListItemView::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> result = new LinkedHashMap<>();

        for (OrderScheduleRowRepository.PlanEndDateView row
                : scheduleRowRepository.findPlanEndDatesOfProductionOrders(
                        ScheduleRowStatus.CONFIRMED, orderIds)) {

            if (row.getOrderId() == null || row.getEndDate() == null) {
                continue;
            }

            result.put(row.getOrderId(),
                    CustomerOrderDateFormatter.format(row.getEndDate()));
        }

        return result;
    }

    /*
     * Идентификаторы строк, у которых позиции вообще могут быть: только
     * заказ «В работе». У «Нового» план ещё не построен, у закрытого или
     * отменённого позиций не осталось, а «Частично выполнен» — уже не
     * очередь, а сдача: колонка обязана показать прочерк.
     */
    private static List<Long> runningIds(
            List<ProductionOrderListItemView> rows) {

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        return rows.stream()
                .filter(row -> row.getStatus() == ProductionOrderStatus.IN_WORK)
                .map(ProductionOrderListItemView::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /*
     * Текущая незакрытая работа каждого экземпляра: «заказ → список
     * работ по номеру экземпляра».
     *
     * Индекс списка — номер экземпляра (instanceIndex у строк плана
     * 0-based, см. OrderSchedulingService), значение — работа первой
     * незакрытой строки. Запрос отсортирован по экземпляру и шагу
     * маршрута, поэтому первая встречная строка и есть ранняя работа:
     * последующие её не перекрывают.
     */
    private Map<Long, List<GroupZnpPositions.Work>> openWorkByInstance(
            List<Long> orderIds) {

        Map<Long, List<GroupZnpPositions.Work>> workByOrder = new LinkedHashMap<>();

        for (OrderScheduleRowRepository.OpenPlanRowView row
                : scheduleRowRepository
                        .findOpenPlanRowsOfProductionOrders(orderIds)) {

            Integer index = row.getInstanceIndex();
            GroupZnpPositions.Work work = workOf(row);

            if (row.getOrderId() == null || index == null || index < 0
                    || work == null) {
                continue;
            }

            List<GroupZnpPositions.Work> works = workByOrder.computeIfAbsent(
                    row.getOrderId(), key -> new ArrayList<>());

            while (works.size() <= index) {
                works.add(null);
            }

            if (works.get(index) == null) {
                works.set(index, work);
            }
        }

        return workByOrder;
    }

    /*
     * Работа строки плана: наименование и его адрес.
     *
     * Подразделение берут со строки — планировщик проставляет адрес
     * конкретного участка; если строка его не задала, остаётся
     * подразделение самой операции. Пустое наименование — не работа,
     * такую строку колонка пропускает.
     */
    private static GroupZnpPositions.Work workOf(
            OrderScheduleRowRepository.OpenPlanRowView row) {

        String operation = trim(row.getOperationName());

        if (operation == null) {
            return null;
        }

        String unit = trim(row.getUnitName());

        return new GroupZnpPositions.Work(
                unit == null ? trim(row.getOperationUnitName()) : unit,
                operation
        );
    }

    /*
     * Номера ячеек, внесённых в заказ, в порядке внесения: i-й элемент
     * списка — экземпляр с номером i.
     *
     * Порядок строк задаёт само сопоставление (см.
     * SerialProductionOrderRepository#findAttachedCellsByOrderIds),
     * поэтому менять его здесь нельзя.
     */
    private Map<Long, List<String>> attachedNumbers(List<Long> orderIds) {

        Map<Long, List<String>> numbersByOrder = new LinkedHashMap<>();

        for (AttachedCellView cell : serialProductionOrderRepository
                .findAttachedCellsByOrderIds(orderIds)) {

            if (cell.getOrderId() == null) {
                continue;
            }

            numbersByOrder
                    .computeIfAbsent(cell.getOrderId(), key -> new ArrayList<>())
                    .add(cell.getFullNumber());
        }

        return numbersByOrder;
    }

    /*
     * Позиции экземпляров → сводка по работам.
     *
     * Работа в подписи одна: диспетчер читает список строкой, а не
     * отчётом. Выбирается работа, на которой стоит больше позиций, а
     * при равенстве — встреченная раньше (обход идёт по номерам
     * экземпляров). Полный разбор по всем работам остаётся в
     * подсказке.
     *
     * Позиций нет, а незакрытые работы есть — заказ только взят в
     * работу и склад ещё не внёс ячейки (или все внесённые ушли в
     * брак). Пустая клетка при статусе «В работе» читается как ошибка
     * плана, поэтому подписью становится первая работа маршрута без
     * счёта: адрес заказа известен уже сейчас, считать нечего.
     */
    private static GroupZnpPositions summarize(
            List<GroupZnpPositions.Work> workOfInstance,
            List<String> numbers,
            Set<String> defective) {

        Map<GroupZnpPositions.Work, Long> byWork = new LinkedHashMap<>();

        for (int index = 0; index < numbers.size(); index++) {

            String number = numbers.get(index);

            if (number == null || isDefective(number, defective)
                    || index >= workOfInstance.size()) {
                continue;
            }

            GroupZnpPositions.Work work = workOfInstance.get(index);

            if (work == null) {
                continue;
            }

            byWork.merge(work, 1L, Long::sum);
        }

        if (byWork.isEmpty()) {
            return firstWork(workOfInstance);
        }

        GroupZnpPositions.Work leading = null;
        long leadingCount = 0L;

        for (Map.Entry<GroupZnpPositions.Work, Long> entry : byWork.entrySet()) {

            if (entry.getValue() > leadingCount) {
                leading = entry.getKey();
                leadingCount = entry.getValue();
            }
        }

        return new GroupZnpPositions(
                leading.unitName(),
                leading.operationName(),
                leadingCount,
                byWork
        );
    }

    /*
     * Первая незакрытая работа маршрута — подпись заказа без позиций.
     *
     * Обход по номерам экземпляров: у первого экземпляра список работ
     * начинается с ранней работы маршрута, она и становится подписью.
     * Счёт нулевой — ячеек, стоящих на работе, ещё нет.
     */
    private static GroupZnpPositions firstWork(
            List<GroupZnpPositions.Work> workOfInstance) {

        for (GroupZnpPositions.Work work : workOfInstance) {

            if (work != null) {
                return new GroupZnpPositions(
                        work.unitName(),
                        work.operationName(),
                        0L,
                        Map.of()
                );
            }
        }

        return GroupZnpPositions.EMPTY;
    }

    /*
     * Позиция в браке.
     *
     * Заявка заводится по номеру в свободной записи («1212 00022»,
     * «№121200022»), поэтому сравнение идёт по цифрам — тот же
     * разбор, что в PendingDefectService#normalize.
     */
    private static boolean isDefective(String number, Set<String> defective) {

        if (defective.isEmpty()) {
            return false;
        }

        String digits = digitsOf(number);

        return !digits.isEmpty()
                && defective.stream().anyMatch(value -> digits.equals(digitsOf(value)));
    }

    private static String digitsOf(String value) {

        if (value == null) {
            return "";
        }

        StringBuilder digits = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {

            char symbol = value.charAt(i);

            if (symbol >= '0' && symbol <= '9') {
                digits.append(symbol);
            }
        }

        return digits.toString();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
