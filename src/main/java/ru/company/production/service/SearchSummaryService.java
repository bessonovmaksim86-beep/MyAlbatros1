package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.SearchSummaryView;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.WorkPlace;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.repository.ZnpCellLinkView;
import ru.company.production.repository.ZnpCellPassageView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Общая сводка по найденному объекту для поиска.
 *
 * Ищет объект по категории и идентификатору и собирает нейтральную
 * «общую информацию»: заголовок, статус и плоский перечень свойств.
 * Интерфейс сводки один на все категории, поэтому пользователю не
 * нужно разбираться, в какой отдел его забросил поиск.
 *
 * Чтение идёт внутри readOnly-транзакции: open-in-view выключен, а
 * свойства берутся через ленивые связи (заказчик, изделие, уровень),
 * которые за пределами сессии дали бы LazyInitializationException.
 * Поэтому значения извлекаются здесь, а в шаблон уходит уже готовый
 * список строк.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchSummaryService {

    private final TechProcessRepository techProcessRepository;
    private final ProductClassifierRepository classifierRepository;
    private final OperationRepository operationRepository;
    private final WorkPlaceRepository workPlaceRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductSerialRepository productSerialRepository;
    private final CellReceivingBatchRepository receivingBatchRepository;
    private final SerialProductionOrderRepository serialOrderRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;

    /*
     * Паспорт изделия нужен сводке только как признак: есть ли у номера
     * история перемещений и, значит, стоит ли давать ссылку на неё.
     */
    private final ProductPassportService passportService;

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Сводка по объекту или null, если категория неизвестна либо
     * запись не найдена — контроллер покажет нейтральное «не найдено»,
     * а не упадёт с ошибкой.
     */
    public SearchSummaryView build(String category, Long id) {
        if (category == null || id == null) {
            return null;
        }

        return switch (category) {
            case "techprocess" -> techProcess(id);
            case "classifier" -> classifier(id);
            case "operation" -> operation(id);
            case "workplace" -> workPlace(id);
            case "customerorder" -> customerOrder(id);
            case "productionorder" -> productionOrder(id);
            case "serial" -> productSerial(id);
            case "receiving" -> receiving(id);
            default -> null;
        };
    }

    private SearchSummaryView techProcess(Long id) {
        TechProcess process = techProcessRepository
                .findHeaderById(id)
                .orElse(null);

        if (process == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Код маршрута", process.getCode());

        ProductClassifier classifier = process.getProductClassifier();

        if (classifier != null) {
            add(fields, "Изделие", classifierSignature(classifier));
            add(fields, "Тип изделия", classifier.getProductType() == null
                    ? null
                    : classifier.getProductType().getDisplayName());
        }

        add(fields, "Назначение", process.getNote());

        /*
         * Визуализация графа маршрута — основное действие для техпроцесса.
         *
         * Адрес ведёт в нейтральную сводку (см. SearchSummaryController),
         * а не в раздел технологов: поиск один на всю систему, и забрасывать
         * пользователя в чужое боковое меню было бы переходом между ролями.
         */
        return new SearchSummaryView(
                "techprocess",
                process.getCode(),
                "Техпроцесс",
                process.isActive() ? "Действующий" : "Недействующий",
                fields,
                null,
                "/search/graph/" + id,
                "Граф зависимостей"
        );
    }

    private SearchSummaryView classifier(Long id) {
        ProductClassifier classifier = classifierRepository
                .findDetailedById(id)
                .orElse(null);

        if (classifier == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Код изделия",
                classifier.getCode() == null
                        ? null
                        : String.valueOf(classifier.getCode()));

        add(fields, "Наименование", classifier.getFullDisplayName());

        add(fields, "Тип изделия", classifier.getProductType() == null
                ? null
                : classifier.getProductType().getDisplayName());

        return new SearchSummaryView(
                "classifier",
                classifier.getFullDisplayName(),
                "Изделие классификатора",
                classifier.getProductType() == null
                        ? null
                        : classifier.getProductType().getDisplayName(),
                fields
        );
    }

    private SearchSummaryView operation(Long id) {
        Operation operation = operationRepository
                .findDetailedById(id)
                .orElse(null);

        if (operation == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Наименование", operation.getOperationName() == null
                ? null
                : operation.getOperationName().getName());

        add(fields, "Тип операции", operation.getOperationType() == null
                ? null
                : operation.getOperationType().getName());

        add(fields, "Подразделение", operation.getOrganizationUnit() == null
                ? null
                : operation.getOrganizationUnit().getName());

        add(fields, "Роль исполнителя",
                operation.getExecutorRole() == null
                        ? null
                        : operation.getExecutorRole().getDisplayName());

        return new SearchSummaryView(
                "operation",
                operation.getOperationName() == null
                        ? "Операция"
                        : operation.getOperationName().getName(),
                "Операция",
                null,
                fields
        );
    }

    private SearchSummaryView workPlace(Long id) {
        WorkPlace place = workPlaceRepository
                .findDetailedById(id)
                .orElse(null);

        if (place == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Код места", place.getCode());
        add(fields, "Наименование", place.getName());

        add(fields, "Тип места", place.getWorkPlaceType() == null
                ? null
                : place.getWorkPlaceType().getName());

        add(fields, "Подразделение", place.getOrganizationUnit() == null
                ? null
                : place.getOrganizationUnit().getName());

        add(fields, "Роль исполнителя",
                place.getExecutorRole() == null
                        ? null
                        : place.getExecutorRole().getDisplayName());

        return new SearchSummaryView(
                "workplace",
                place.getCode() + " — " + place.getName(),
                "Рабочее место",
                place.isActive() ? "Действующее" : "Недействующее",
                fields
        );
    }

    private SearchSummaryView customerOrder(Long id) {
        CustomerOrder order = customerOrderRepository
                .findHeaderById(id)
                .orElse(null);

        if (order == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Номер заказа", order.getNumber());
        add(fields, "Заказчик", order.getCustomer() == null
                ? null
                : order.getCustomer().getName());
        add(fields, "Вид заказа", order.getOrderTypeDisplayName());
        add(fields, "Дата заказа", order.getFormattedOrderDate());
        add(fields, "Срок исполнения", order.getFormattedDueDate());
        add(fields, "Приоритет", order.getPriorityDisplayName());
        add(fields, "Договор", order.getContractNumber());

        return new SearchSummaryView(
                "customerorder",
                order.getNumber(),
                "Заказ покупателя (ЗП)",
                order.getStatus() == null
                        ? null
                        : order.getStatus().getDisplayName(),
                fields
        );
    }

    private SearchSummaryView productionOrder(Long id) {
        ProductionOrder order = productionOrderRepository
                .findDetailedById(id)
                .orElse(null);

        if (order == null) {
            return null;
        }

        /*
         * Групповые ЗНП (V38): заказ ведётся по техпроцессу, и
         * пользователю важнее видеть, где сейчас стоит группа ячеек
         * и как каждая из них прошла маршрут, чем пустые поля
         * «Заказ покупателя» / «Родительский заказ».
         */
        if (order.isGroupZnp()) {
            return productionOrderGroupZnp(id, order);
        }

        /*
         * Обычный выпуск (ЗП) или комплектование — показываем
         * стандартные поля.
         */
        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Номер заказа", order.getNumber());
        add(fields, "Уровень", order.getLevelDisplayName());
        add(fields, "Заказ покупателя", order.getCustomerOrderNumber());
        add(fields, "Родительский заказ", order.getParentOrderNumber());
        add(fields, "Операция", order.getOperationName());

        List<ZnpCellLinkView> cells =
                serialOrderRepository.findCellsByOrderId(id);

        add(fields, "Номер ячейки", attachedCellNumbers(cells));

        add(fields, "Дата заказа", order.getFormattedOrderDate());
        add(fields, "Срок исполнения", order.getFormattedDueDate());

        return new SearchSummaryView(
                "productionorder",
                order.getNumber(),
                "Заказ на производство",
                order.getStatusDisplayName(),
                fields
        );
    }

    /*
     * ============================================================
     * Групповой ЗНП — сводка поиска с техпроцессом
     * ============================================================
     *
     * Заказ вида GROUP_CELL, GROUP_DEVICE, CELL_REPAIR или
     * SUBASSEMBLY_REPAIR ведётся по техпроцессу (V40), и для него
     * важно показать не пустые поля заказа покупателя, а:
     *
     *   1. Текущее место — где группа ячеек стоит прямо сейчас;
     *   2. Представление изделия — номер и наименование;
     *   3. Пошаговое прохождение ТП — каждая операция с прогрессом
     *      ячеек, исполнителем, планом и фактом.
     *
     * Сводка строится по строкам плана OrderScheduleRow (V56), где
     * каждая строка = один экземпляр изделия на одной операции.
     * Запрос агрегирует данные по seq (шагу маршрута) и instanceIndex
     * (экземпляру), чтобы построить представление изделия с его
     * прохождением.
     *
     * ЕДИНЫЙ ЗАПРОС ЯЧЕЕК (V101). Раньше сводка вызывала два разных
     * запроса: findCellsByOrderId (только номер) и findRouteStepsOf...
     * (план). Теперь findPassageCellsByOrderId отдаёт номер, наименование,
     * тип изделия и отметку готовности cellReadyAt — это всё, что нужно
     * для kindLabel и itemLabel.
     *
     * СТРОКА ПОДПИСИ "—". Выводить "—" когда нечего показать — значит
     * притвориться, что данные есть. Поэтому пустое значение всегда
     * превращается в null, а null означает «данных нет», а не «прочерк».
     */
    private SearchSummaryView productionOrderGroupZnp(
            Long orderId, ProductionOrder order) {

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Номер заказа", order.getNumber());
        add(fields, "Вид заказа", order.getZnpKindDisplayName());
        add(fields, "Дата заказа", order.getFormattedOrderDate());
        add(fields, "Срок исполнения", order.getFormattedDueDate());

        if (order.getProductClassifier() != null) {
            add(fields, "Изделие заказа", order.getClassifierLabel());
        }

        add(fields, "Количество",
                order.getQuantity() != null
                        ? order.getFormattedQuantity()
                        : null);

        /*
         * Состав заказа — ЕДИНЫЙ ЗАПРОС с полными реквизитами.
         *
         * findPassageCellsByOrderId отдаёт номер, наименование, тип
         * изделия и отметку готовности. Порядок — по моменту внесения,
         * поэтому i-я ячейка = экземпляр плана i.
         */
        List<ZnpCellPassageView> cells =
                serialOrderRepository.findPassageCellsByOrderId(orderId);

        add(fields, "Внесено ячеек",
                cells.isEmpty() ? null
                        : String.valueOf(cells.size()));

        /*
         * Строка плана — один запрос на все данные.
         *
         * findRouteStepsOfProductionOrder отдаёт seq, экземпляр,
         * операцию, дату, исполнителя, подразделение — всё для
         * построения текущего места и проходов изделий.
         */
        List<OrderScheduleRowRepository.RouteStepRow> allSteps =
                scheduleRowRepository
                        .findRouteStepsOfProductionOrder(orderId);

        /*
         * Текущее место — где позиции стоят прямо сейчас.
         */
        SearchSummaryView.CurrentLocation currentLocation =
                buildCurrentLocation(allSteps);

        /*
         * Матрица прохождения ТП: строки = операции, колонки = ячейки.
         */
        List<SearchSummaryView.OperationPassage> operationPassages =
                buildOperationPassagesMatrix(cells, allSteps);

        /*
         * Сводка техпроцесса: код, изделие, сколько работ проведено.
         */
        SearchSummaryView.TechProcessSummary tps =
                buildTechProcessSummary(order, operationPassages);

        return new SearchSummaryView(
                "productionorder",
                order.getNumber(),
                "Заказ на производство",
                order.getStatusDisplayName(),
                fields,
                null,
                null,
                null,
                currentLocation,
                operationPassages,
                tps
        );
    }

    /*
     * Текущее место: подразделение и работа, где стоит большинство
     * ячеек группы.
     *
     * Принимает строки плана, а не заказ — они уже загружены единым
     * запросом, повторный вызов был бы лишним обращением к БД.
     */
    private SearchSummaryView.CurrentLocation buildCurrentLocation(
            List<OrderScheduleRowRepository.RouteStepRow> steps) {

        if (steps.isEmpty()) {
            return null;
        }

        /*
         * Группируем незакрытые строки по работе: считаем, сколько
         * экземпляров на каждой. Работа с максимумом экземпляров —
         * текущее место.
         */
        Map<String, List<OrderScheduleRowRepository.RouteStepRow>>
                byWork = new LinkedHashMap<>();

        for (OrderScheduleRowRepository.RouteStepRow row : steps) {
            if (row.getCompletedAt() != null) {
                continue;
            }

            String key = row.getOperationName() + "|"
                    + blankToNull(row.getUnitName())
                    + "|" + blankToNull(row.getOperationUnitName());

            byWork.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(row);
        }

        if (byWork.isEmpty()) {
            return null;
        }

        /*
         * Работа с максимумом экземпляров.
         */
        Map.Entry<String, List<OrderScheduleRowRepository.RouteStepRow>>
                leading = null;

        for (Map.Entry<String, List<OrderScheduleRowRepository.RouteStepRow>> entry : byWork.entrySet()) {
            if (leading == null
                    || entry.getValue().size() > leading.getValue().size()) {
                leading = entry;
            }
        }

        if (leading == null) {
            return null;
        }

        List<OrderScheduleRowRepository.RouteStepRow> leadingRows
                = leading.getValue();
        String unitName = blankToNull(leadingRows.get(0).getUnitName());
        if (unitName == null) {
            unitName = blankToNull(
                    leadingRows.get(0).getOperationUnitName());
        }

        String operationName = leadingRows.get(0).getOperationName();
        long count = leadingRows.size();

        String description = unitName != null
                ? unitName + " (" + operationName + " — " + count + " шт.)"
                : operationName + " — " + count + " шт.";

        /*
         * Если позиции разбросаны по нескольким работам — подсказка.
         */
        String others = null;
        if (byWork.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<OrderScheduleRowRepository.RouteStepRow>> entry : byWork.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                String[] parts = entry.getKey().split("\\|");
                String opName = parts[0];
                String u = blankToNull(parts[1]);
                if (u == null) {
                    u = blankToNull(parts[2]);
                }
                String label = u != null
                        ? u + " (" + opName + ")"
                        : opName;
                sb.append(label).append(" — ")
                        .append(entry.getValue().size());
            }
            others = "Позиции разбросаны: " + sb.toString();
        }

        return new SearchSummaryView.CurrentLocation(
                unitName, description, others);
    }

    /*
     * Матрица прохождения техпроцесса: строка = работа маршрута,
     * колонки = внесённые в заказ ячейки.
     *
     * Строки идут в порядке маршрута: запрос отсортирован по seq, а
     * группировка — по LinkedHashMap, поэтому первая встреченная работа
     * оказывается ранней, а не произвольной.
     *
     * Ячейка сопоставляется экземпляру плана по правилу V56: i-я по
     * внесению ячейка = экземпляр с индексом i. Отдельной связи
     * «строка плана — серийный номер» в схеме нет, её заменяет
     * порядок внесения.
     */
    private List<SearchSummaryView.OperationPassage> buildOperationPassagesMatrix(
            List<ZnpCellPassageView> cells,
            List<OrderScheduleRowRepository.RouteStepRow> allSteps) {

        /*
         * Матрица без колонек бессмысленна: строки плана есть почти у
         * любого заказа в работе, а изделий в заказе ещё нет. Пустой
         * список — сигнал шаблону показать объяснение, а не таблицу
         * прочерков.
         */
        if (cells.isEmpty()) {
            return List.of();
        }

        Map<Long, List<OrderScheduleRowRepository.RouteStepRow>>
                byOperation = new LinkedHashMap<>();

        for (OrderScheduleRowRepository.RouteStepRow row : allSteps) {
            Long opId = row.getOperationId();
            if (opId == null) {
                continue;
            }
            byOperation.computeIfAbsent(opId, k -> new ArrayList<>())
                    .add(row);
        }

        /*
         * Порядок обхода важен: колонки матрицы должны идти одинаково
         * во всех строках, иначе одна ячейка прыгнула бы между
         * столбцами. TreeMap фиксирует порядок по номеру экземпляра.
         */
        Map<Integer, ZnpCellPassageView> cellByInstance = new TreeMap<>();

        for (int i = 0; i < cells.size(); i++) {
            cellByInstance.put(i, cells.get(i));
        }

        List<SearchSummaryView.OperationPassage> result =
                new ArrayList<>(byOperation.size());

        LocalDate today = LocalDate.now();

        for (List<OrderScheduleRowRepository.RouteStepRow> rows
                : byOperation.values()) {
            result.add(buildOperationPassage(rows, cellByInstance, today));
        }

        return result;
    }

    /*
     * Одна строка матрицы: работа маршрута и её ячейки.
     *
     * Агрегатные поля (исполнитель, план, факт, статус) описывают
     * работу целиком — так строка читается даже когда ячеек много и
     * колонки ушли за край экрана.
     */
    private SearchSummaryView.OperationPassage buildOperationPassage(
            List<OrderScheduleRowRepository.RouteStepRow> rows,
            Map<Integer, ZnpCellPassageView> cellByInstance,
            LocalDate today) {

        OrderScheduleRowRepository.RouteStepRow first = rows.get(0);

        /*
         * Ячейки строки считаются первыми: работа закрыта тогда, когда
         * её провели все внесённые в заказ изделия, а не все строки
         * плана. Строк плана может быть больше, чем ячеек, — тогда
         * «проведено» по строкам соврало бы о готовности группы.
         */
        List<SearchSummaryView.CellPassage> cells =
                buildCellPassages(rows, cellByInstance, today);

        int passed = 0;
        boolean overdue = false;

        for (SearchSummaryView.CellPassage cell : cells) {
            if (cell.isCompleted()) {
                passed++;
            } else if (cell.isOverdue()) {
                overdue = true;
            }
        }

        int total = cells.size();
        boolean completed = total > 0 && passed == total;

        /*
         * Исполнитель незакрытой работы важнее исполнителя закрытой:
         * строка в работе отвечает за группу сейчас.
         */
        String executor = "не назначен";
        boolean assigned = false;

        for (OrderScheduleRowRepository.RouteStepRow r : rows) {
            String name = executorLabel(r);
            if (name != null) {
                assigned = true;
                if (r.getCompletedAt() == null) {
                    executor = name;
                    break;
                }
                executor = name;
            }
        }

        String statusLabel;
        String statusModifier;

        if (total == 0) {
            statusLabel = "нет ячеек";
            statusModifier = "muted";
        } else if (completed) {
            statusLabel = "выполнено";
            statusModifier = "ok";
        } else if (overdue) {
            statusLabel = "просрочено";
            statusModifier = "danger";
        } else if (passed > 0) {
            statusLabel = "частично";
            statusModifier = "progress";
        } else if (assigned) {
            statusLabel = "в работе";
            statusModifier = "progress";
        } else {
            statusLabel = "ожидает";
            statusModifier = "muted";
        }

        /*
         * Факт работы — последний по времени: группа закрывает
         * работу не одновременно, и важен момент, когда её закрыли.
         *
         * Строки плана может быть больше, чем внесённых ячеек:
         * экземпляр без изделия в заказе остаётся с пустой датой, и
         * наивный обход споткнулся бы о неё на isAfter().
         */
        String actualLabel = "—";

        if (completed) {
            java.time.LocalDateTime last = null;

            for (OrderScheduleRowRepository.RouteStepRow r : rows) {
                java.time.LocalDateTime moment = r.getCompletedAt();

                if (moment == null) {
                    continue;
                }

                if (last == null || moment.isAfter(last)) {
                    last = moment;
                }
            }

            actualLabel = formatDate(last);
        }

        return new SearchSummaryView.OperationPassage(
                first.getOperationName(),
                unitOf(rows),
                executor,
                formatDate(first.getPlannedDate()),
                actualLabel,
                completed,
                overdue,
                statusLabel,
                statusModifier,
                passed,
                total,
                cells
        );
    }

    /*
     * Ячейки, прошедшие (или нет) одну работу маршрута.
     *
     * Обход идёт по внесённым ячейкам, а не по строкам плана: колонок
     * должно быть ровно столько, сколько изделий в заказе, иначе
     * таблица показала бы работы, которых у изделий ещё нет.
     */
    private List<SearchSummaryView.CellPassage> buildCellPassages(
            List<OrderScheduleRowRepository.RouteStepRow> rows,
            Map<Integer, ZnpCellPassageView> cellByInstance,
            LocalDate today) {

        Map<Integer, OrderScheduleRowRepository.RouteStepRow>
                rowByInstance = new HashMap<>();

        for (OrderScheduleRowRepository.RouteStepRow r : rows) {
            if (r.getInstanceIndex() != null) {
                rowByInstance.put(r.getInstanceIndex(), r);
            }
        }

        List<SearchSummaryView.CellPassage> cells = new ArrayList<>();

        for (Map.Entry<Integer, ZnpCellPassageView> entry
                : cellByInstance.entrySet()) {

            ZnpCellPassageView cell = entry.getValue();
            OrderScheduleRowRepository.RouteStepRow row =
                    rowByInstance.get(entry.getKey());

            boolean completed = row != null && row.getCompletedAt() != null;
            boolean overdue = row != null && !completed
                    && row.getPlannedDate() != null
                    && row.getPlannedDate().isBefore(today);

            cells.add(new SearchSummaryView.CellPassage(
                    cell.getSerialId(),
                    entry.getKey() + 1,
                    cell.getFullNumber(),
                    cell.getCellReadyAt() != null ? "Ячейка" : "Подсборка",
                    row == null ? "—" : executorLabelOrDash(row),
                    row == null ? "—" : formatDate(row.getPlannedDate()),
                    completed ? formatDate(row.getCompletedAt()) : "—",
                    completed,
                    overdue
            ));
        }

        return cells;
    }

    /* ФИО исполнителя строки плана; логин — запасная подпись. */
    private String executorLabel(OrderScheduleRowRepository.RouteStepRow row) {
        String name = blankToNull(row.getExecutorName());

        return name != null ? name : blankToNull(row.getExecutorLogin());
    }

    private String executorLabelOrDash(
            OrderScheduleRowRepository.RouteStepRow row) {

        String name = executorLabel(row);

        return name == null ? "не назначен" : name;
    }

    /*
     * Подразделение работы: адрес строки плана надёжнее подразделения
     * самой операции — распределение могло назначить другой участок.
     */
    private String unitOf(
            List<OrderScheduleRowRepository.RouteStepRow> rows) {

        for (OrderScheduleRowRepository.RouteStepRow r : rows) {
            String unit = blankToNull(r.getUnitName());
            if (unit != null) {
                return unit;
            }
        }

        for (OrderScheduleRowRepository.RouteStepRow r : rows) {
            String unit = blankToNull(r.getOperationUnitName());
            if (unit != null) {
                return unit;
            }
        }

        return null;
    }
    /*
     * Сводка техпроцесса: код, изделие, сколько работ проведено.
     *
     * Считается по готовым строкам матрицы, а не по строкам плана
     * напрямую: иначе число в заголовке и галочки в таблице считались
     * бы по разным правилам, и «7 из 20» против семи строк «выполнено»
     * выглядело бы ошибкой данных.
     */
    private SearchSummaryView.TechProcessSummary buildTechProcessSummary(
            ProductionOrder order,
            List<SearchSummaryView.OperationPassage> passages) {

        TechProcess tp = order.getTechProcess();
        if (tp == null || tp.getCode() == null) {
            return null;
        }

        String itemLabel = "—";
        if (tp.getProductClassifier() != null) {
            ProductClassifier pc = tp.getProductClassifier();
            String name = pc.getDisplayName();
            Integer code = pc.getCode();
            itemLabel = code + (name != null && !name.isBlank()
                    ? " " + name : "");
        }

        /*
         * Знаменатель — длина маршрута, а не число строк плана: заказ
         * мог встать в план только частью работ, и «3 из 3» при
         * двадцати операциях маршрута скрыла бы остальной путь.
         */
        int totalOps = tp.getOperations() == null
                ? passages.size()
                : Math.max(tp.getOperations().size(), passages.size());

        int passedOps = 0;

        for (SearchSummaryView.OperationPassage passage : passages) {
            if (passage.isCompleted()) {
                passedOps++;
            }
        }

        String progressLabel = totalOps == 0
                ? "нет работ"
                : passedOps + " из " + totalOps + " работ проведено";

        return new SearchSummaryView.TechProcessSummary(
                tp.getCode(), itemLabel, totalOps,
                passedOps, progressLabel);
    }

    /*
     * ============================================================
     * Вспомогательные методы
     * ============================================================
     */

    private static String formatDate(LocalDate date) {
        return date == null ? "—" : date.format(RU_DATE);
    }

    private static String formatDate(java.time.LocalDateTime dt) {
        if (dt == null) return "—";
        return dt.format(RU_DATE);
    }

    /*
     * Сводка по серийному номеру изделия.
     *
     * Номер ищут чаще всего по QR или строке из 1–2 разделов, и
     * пользователю важно увидеть не только сам номер, но и где он
     * сейчас: заказ, техпроцесс, отметки контроля и приёмки.
     *
     * Номера партий прихода в реестре ещё нет (он зарезервирован),
     * поэтому строка выдачи по приходному ордеру ведёт на партию,
     * а не на серийник: партия читается отдельно (см. batch()).
     */
    private SearchSummaryView productSerial(Long id) {
        ProductSerial serial = productSerialRepository
                .findDetailedById(id)
                .orElse(null);

        if (serial == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Серийный номер", serial.getFullNumber());
        add(fields, "Тип изделия", serial.getTypeName());
        add(fields, "Изделие", serial.getDisplayName());
        add(fields, "Заказ на производство", serial.getProductionOrderNumber());
        add(fields, "Заказ покупателя", serial.getCustomerOrderNumber());
        add(fields, "Номер ЗНП", serial.getZnpNumber());
        add(fields, "Приходный ордер", serial.getIncomingOrderNumber());
        add(fields, "Входной контроль", serial.getInputControlMarkLabel());
        add(fields, "Приёмка на склад", serial.getAcceptedMarkLabel());

        /*
         * Паспорт дают только при наличии истории: ссылка на пустую
         * хронологию была бы обещанием, которое страница не выполнит, —
         * номер без единого события reads как ошибка поиска.
         */
        return new SearchSummaryView(
                "serial",
                serial.getFullNumber(),
                "Серийный номер",
                serial.getStatus() == null
                        ? null
                        : serial.getStatus().getDisplayName(),
                fields,
                passportService.exists(serial.getFullNumber())
                        ? "/passport/" + serial.getFullNumber()
                        : null
        );
    }

    /*
     * Сводка по приходу склада полуфабрикатов.
     *
     * Приход ищут по номеру приходного ордера, и найти его нужно даже
     * тогда, когда номера партии ещё не внесены в реестр: черновик
     * прихода — штатное состояние, а не ошибка.
     */
    private SearchSummaryView receiving(Long id) {
        CellReceivingBatch batch = receivingBatchRepository
                .findDetailedById(id)
                .orElse(null);

        if (batch == null) {
            return null;
        }

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Приходный ордер", batch.getIncomingOrderNumber());
        add(fields, "Поставщик", batch.getSupplierName());
        add(fields, "Изделие", batch.getCellLabel());
        add(fields, "Изменение", batch.getChangeNumber());
        add(fields, "Количество", String.valueOf(batch.getQuantity()));
        add(fields, "Диапазон номеров", batch.getNumberRangeLabel());
        add(fields, "Принято на склад", batch.getAcceptedQuantity() == null
                ? null
                : String.valueOf(batch.getAcceptedQuantity()));
        add(fields, "Оформил", batch.getAuthorName());
        add(fields, "Дата прихода", batch.getFormattedCreatedAt());
        add(fields, "Примечание", batch.getNote());

        return new SearchSummaryView(
                "receiving",
                batch.getIncomingOrderNumber(),
                "Приход склада (приходный ордер)",
                batch.getStatusLabel(),
                fields
        );
    }

    /*
     * Внесённые в заказ ячейки одной строкой: «121200022, 121200023».
     *
     * Пустой состав отдаётся null, а не прочерком: строку не добавляют
     * вовсе (см. add()), и сводка не притворяется, что состав есть.
     */
    private String attachedCellNumbers(List<ZnpCellLinkView> cells) {
        if (cells.isEmpty()) {
            return null;
        }

        StringBuilder numbers = new StringBuilder();

        for (ZnpCellLinkView cell : cells) {
            if (numbers.length() > 0) {
                numbers.append(", ");
            }

            numbers.append(cell.getCellNumber());
        }

        return numbers.toString();
    }

    /* Подпись изделия: «1212 ЯПР56» либо род датчика, если имени нет. */
    private String classifierSignature(ProductClassifier classifier) {
        String name = classifier.getFullDisplayName();

        return classifier.getCode()
                + (name != null && !name.isBlank() ? " " + name : "");
    }

    /*
     * Строка сводки добавляется только при непустом значении: пустое
     * поле — это отсутствие данных, а не «значение прочерк», и держать
     * в перечне строку «Договор: —» смысла нет.
     */
    private void add(List<SearchSummaryView.Field> fields,
                     String label,
                     String value) {

        if (value != null && !value.isBlank()) {
            fields.add(new SearchSummaryView.Field(label, value));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() || "—".equals(value.trim())
                ? null
                : value.trim();
    }
}
