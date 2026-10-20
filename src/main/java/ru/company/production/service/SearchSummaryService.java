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
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.WorkPlaceRepository;

import java.util.ArrayList;
import java.util.List;

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

    /*
     * Паспорт изделия нужен сводке только как признак: есть ли у номера
     * история перемещений и, значит, стоит ли давать ссылку на неё.
     */
    private final ProductPassportService passportService;

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

        List<SearchSummaryView.Field> fields = new ArrayList<>();

        add(fields, "Номер заказа", order.getNumber());
        add(fields, "Уровень", order.getLevelDisplayName());
        add(fields, "Заказ покупателя", order.getCustomerOrderNumber());
        add(fields, "Родительский заказ", order.getParentOrderNumber());
        add(fields, "Операция", order.getOperationName());
        add(fields, "Техпроцесс", blankToNull(order.getTechProcessCode()));
        add(fields, "Дата заказа", order.getFormattedOrderDate());
        add(fields, "Срок исполнения", order.getFormattedDueDate());

        /*
         * Изделие и количество есть только у групповых и ремонтных
         * заказов; у выпуска и комплектования их нет, и пустые строки
         * в сводке были бы шумом — add() их отбрасывает по значению.
         */
        if (order.isKindApplicable()) {
            add(fields, "Изделие заказа", blankToNull(
                    order.getClassifierLabel()));
            add(fields, "Количество", order.getFormattedQuantity());
        }

        return new SearchSummaryView(
                "productionorder",
                order.getNumber(),
                "Заказ на производство",
                order.getStatusDisplayName(),
                fields
        );
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
