package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.PassportView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.QuarantineTransfer;
import ru.company.production.entity.RepairOrder;
import ru.company.production.entity.SerialMovementEvent;
import ru.company.production.entity.SerialMovementType;
import ru.company.production.entity.UnitHandover;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialMovementEventRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.ZnpCellLinkView;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Паспорт изделия: хронология ячейки или подсборки вокруг номера.
 *
 * Источник — журнал перемещений, а не реестр: строка реестра показывает
 * только текущее состояние и только для номеров, в реестр внесённых, а
 * в изолятор попадают и номера партий прихода ещё до оприходования.
 * Поэтому паспорт собирают по журналу, и он обязан открыться и по такому
 * номеру.
 *
 * Читает внутри readOnly-транзакции: {@code open-in-view} выключен, а
 * подписи строк обращаются к ленивым связям исполнителя, заказов,
 * изделия и партии.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductPassportService {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SerialMovementEventRepository journalRepository;
    private final ProductSerialRepository serialRepository;
    private final SerialProductionOrderRepository orderLinkRepository;

    /**
     * Паспорт номера или {@code null}, если по номеру нет ни одного
     * события: контроллер покажет нейтральное «данных нет», а не пустую
     * страницу.
     */
    public PassportView build(String fullNumber) {

        String number = normalize(fullNumber);

        if (number == null) {
            return null;
        }

        List<SerialMovementEvent> history =
                journalRepository.findHistoryByFullNumber(number);

        if (history.isEmpty()) {
            return null;
        }

        SerialMovementEvent last = history.get(history.size() - 1);
        ProductSerial serial = serialOf(number);

        /*
         * Брак по каждой партии прихода: число событий изолятора,
         * ссылающихся на ту же партию. Отдельного счётца брака на
         * партии нет: его выводит карточка входного контроля, а партия
         * знает только принятое количество. Журнал содержит ровно те
         * же попадания в изолятор — по одному на номер (V64), поэтому
         * паспорт считает их по уже загруженной истории.
         */
        Map<Long, Long> batchDefects = batchDefectsOf(history);

        /* Состав каждого заказа истории: номера ячеек, в него вошедших. */
        Map<String, List<String>> znpCells = znpCellsOf(history);

        List<PassportView.PassportEventRow> rows =
                new ArrayList<>(history.size());

        for (SerialMovementEvent event : history) {
            rows.add(rowOf(event, batchDefects, znpCells));
        }

        return new PassportView(
                number,
                itemLabelOf(last, serial),
                last.getCellKind() == null
                        ? "—"
                        : last.getCellKind().getDisplayName(),
                last.getSerialStatus() == null
                        ? "вне реестра"
                        : last.getSerialStatus().getDisplayName(),
                serial != null,
                format(journalRepository.findFirstMoment(number)),
                format(journalRepository.findLastMoment(number)),
                history.stream()
                        .filter(event -> event.getEventType()
                                == SerialMovementType.QUARANTINED)
                        .count(),
                history.stream()
                        .filter(event -> event.getEventType()
                                == SerialMovementType.REPAIR_CANCELLED)
                        .count(),
                history.stream()
                        .filter(event -> event.getEventType()
                                == SerialMovementType.RETURNED_TO_REWORK)
                        .count(),
                rows
        );
    }

    /** Есть ли у номера история — по этому признаку поиск даёт ссылку. */
    public boolean exists(String fullNumber) {

        String number = normalize(fullNumber);

        return number != null
                && journalRepository.existsByFullNumber(number);
    }

    /**
     * Номера, упомянутые в журнале, — строки поиска «Паспорта изделий».
     *
     * Ввод сводят к цифрам: полный номер — девять цифр, и пользователь
     * вводит его со сканера или из документа с дефисами и пробелами, а
     * в журнале номер лежит слитно. Заодно это вычищает «%» и «_»,
     * которые в LIKE работали бы как маски и показали бы весь журнал.
     */
    public List<String> searchNumbers(String term, int limit) {

        String digits = normalize(term);

        return digits == null
                ? List.of()
                : journalRepository.searchFullNumbers(
                        digits, PageRequest.of(0, limit));
    }

    /* ============================================================
       СТРОКА ХРОНОЛОГИИ
       ============================================================ */

    private static PassportView.PassportEventRow rowOf(
            SerialMovementEvent event,
            Map<Long, Long> batchDefects,
            Map<String, List<String>> znpCells) {

        return new PassportView.PassportEventRow(
                titleOf(event),
                event.getEventType().getBadgeClass(),
                format(event.getOccurredAt()),
                actorLabelOf(event.getActor()),
                documentLabelOf(event),
                reasonLabelOf(event),
                stateLabelOf(event),
                quantityLabelOf(event, batchDefects, znpCells),
                cellsOf(event.getProductionOrder(), znpCells)
        );
    }

    /**
     * Заголовок события.
     *
     * Проведение цеховой операции (V87) — единственное событие, чьё
     * note называет саму работу, а не её причину: «Операция проведена»
     * без наименования не ответило бы, что именно закрыли, тогда как
     * для изолятора, возврата и отказа note остаётся причиной и живёт в
     * своей колонке.
     */
    private static String titleOf(SerialMovementEvent event) {

        String title = event.getEventType().getDisplayName();

        if (event.getEventType()
                != ru.company.production.entity
                .SerialMovementType.OPERATION_COMPLETED) {
            return title;
        }

        String operation = event.getNote();

        return operation == null || operation.isBlank()
                ? title
                : "Проведена операция «" + operation.trim() + "»";
    }

    /** Причина события: для проведения работы её уже несёт заголовок. */
    private static String reasonLabelOf(SerialMovementEvent event) {

        if (event.getEventType()
                == ru.company.production.entity
                .SerialMovementType.OPERATION_COMPLETED) {
            return "—";
        }

        return blankToDash(event.getNote());
    }

    /**
     * Документ-источник события.
     *
     * Собирают по всем возможным основаниям сразу: у события ремонта
     * есть ремонтный заказ, у входа в производство — заказ-комплектующий.
     * Перечисляют через «·», потому что ремонтный ЗНП и заказ-комплектующий
     * на производство — разные документы одного периода, и смешивать их
     * в одной подписи нельзя.
     *
     * Приходная партия печатается только там, где у события нет заказа:
     * складской этап обязан называть ордер и поставщика, а цеховой —
     * заказ. Ссылка на приход живёт в журнале и у движений внутри
     * выпуска (номер пришёл из партии), но в цеховой строке она только
     * шумит: путь изделия уже читают по заказу, а партию — по строкам
     * склада выше.
     */
    private static String documentLabelOf(SerialMovementEvent event) {

        List<String> documents = new ArrayList<>();

        RepairOrder repair = event.getRepairOrder();

        if (repair != null) {
            documents.add("ЗНР " + blankToDash(repair.getNumber()));
        }

        ProductionOrder production = event.getProductionOrder();

        if (production != null) {
            documents.add("Заказ " + blankToDash(production.getNumber()));
        }

        CellReceivingBatch batch = event.getReceivingBatch();

        if (batch != null && production == null) {
            documents.add("Приход " + blankToDash(batch.getIncomingOrderNumber())
                    + " · " + blankToDash(batch.getSupplierName()));
        }

        QuarantineTransfer transfer = event.getQuarantineTransfer();

        if (transfer != null) {
            documents.add("Перемещение №" + transfer.getId());
        }

        UnitHandover handover = event.getUnitHandover();

        if (handover != null) {
            documents.add("Передача в " + blankToDash(handover.getToUnitName()));
        }

        return documents.isEmpty() ? "—" : String.join(" · ", documents);
    }

    /**
     * Информация о количестве по документу-источнику события.
     *
     * Заказ печатают первым: движение внутри выпуска ссылается и на
     * партию, из которой номер пришёл, и на заказ, но цеховую строку
     * интересует заказ, а не складской приход — иначе количество
     * прихода повторялось бы в каждой строке ниже по хронологии.
     *
     * Приходная партия печатает «N шт.» или «N / M забр.», когда часть
     * ячеек попала в изолятор.
     */
    private static String quantityLabelOf(
            SerialMovementEvent event,
            Map<Long, Long> batchDefects,
            Map<String, List<String>> znpCells) {

        ProductionOrder production = event.getProductionOrder();

        if (production != null) {

            if (production.getQuantity() != null) {
                return production.getFormattedQuantity() + " шт.";
            }

            List<String> cells = cellsOf(production, znpCells);

            return cells.isEmpty() ? "—" : cells.size() + " шт.";
        }

        CellReceivingBatch batch = event.getReceivingBatch();

        if (batch != null) {

            long defect = batchDefects.getOrDefault(batch.getId(), 0L);

            return defect == 0
                    ? batch.getQuantity() + " шт."
                    : batch.getQuantity() + " / " + defect + " забр.";
        }

        return "—";
    }

    /**
     * Состав заказа по его номеру: номера ячеек, вошедших в состав.
     */
    private static List<String> cellsOf(ProductionOrder production,
                                        Map<String, List<String>> znpCells) {

        if (production == null || production.getNumber() == null
                || production.getNumber().isBlank()) {
            return List.of();
        }

        return znpCells.getOrDefault(production.getNumber(), List.of());
    }

    /**
     * Сколько ячеек забраковано в каждой партии прихода данной истории.
     */
    private static Map<Long, Long> batchDefectsOf(
            List<SerialMovementEvent> history) {

        return history.stream()
                .filter(event -> event.getEventType()
                        == SerialMovementType.QUARANTINED)
                .filter(event -> event.getReceivingBatch() != null)
                .collect(Collectors.groupingBy(
                        event -> event.getReceivingBatch().getId(),
                        Collectors.counting()));
    }

    /**
     * Состав каждого заказа истории: номера ячеек, в него вошедших.
     *
     * Нужен строке «Вошла в выпуск производства»: по одному номеру
     * видно только его самого, а заказ-комплектующий собирает несколько
     * изделий, и без перечня соседних не восстановить, чем укомплектован
     * прибор.
     *
     * Запрос пакетный по номерам заказов: история одного номера
     * ссылается на единицу заказов, но выборка на каждую строку
     * удваивала бы число обращений к базе на странице паспорта.
     */
    private Map<String, List<String>> znpCellsOf(
            List<SerialMovementEvent> history) {

        List<String> orderNumbers = history.stream()
                .map(SerialMovementEvent::getProductionOrder)
                .filter(Objects::nonNull)
                .map(ProductionOrder::getNumber)
                .filter(number -> number != null && !number.isBlank())
                .distinct()
                .toList();

        if (orderNumbers.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> cells = new HashMap<>();

        for (ZnpCellLinkView link
                : orderLinkRepository.findCellsByZnpNumbers(orderNumbers)) {

            cells.computeIfAbsent(link.getZnpNumber(),
                    key -> new ArrayList<>()).add(link.getCellNumber());
        }

        return cells;
    }

    /* ============================================================
       Состояние ячейки на момент события
       ============================================================ */

    /**
     * Состояние ячейки на момент события.
     *
     * Печатают скопированные в строку журнала реквизиты, а не текущие
     * колонки реестра: иначе через месяц паспорт показал бы нынешнюю
     * стадию вместо той, на которой событие произошло.
     */
    private static String stateLabelOf(SerialMovementEvent event) {

        List<String> parts = new ArrayList<>();

        if (event.getCellKind() != null) {
            parts.add(event.getCellKind().getDisplayName());
        }

        if (event.getProductClassifier() != null) {
            parts.add(blankToDash(event.getProductClassifier().getDisplayName()));
        }

        if (event.getSerialStatus() != null) {
            parts.add(event.getSerialStatus().getDisplayName());
        }

        if (event.getInputControlType() != null) {
            parts.add(event.getInputControlType().getDisplayName());
        }

        return parts.isEmpty() ? "—" : String.join(" · ", parts);
    }

    /* ============================================================
       ПОДПИСИ ЗАГОЛОВКА
       ============================================================ */

    /**
     * Наименование изделия.
     *
     * Берут из строки реестра, а событие — источник на случай номера вне
     * реестра: у такого номера изделия нет в {@code product_serials}, но
     * журнал его помнит, и паспорт обязан показывать, что это за изделие.
     */
    private static String itemLabelOf(SerialMovementEvent last,
                                      ProductSerial serial) {

        if (serial != null) {
            return serial.getDisplayName();
        }

        return last.getProductClassifier() == null
                ? "Номер вне реестра"
                : last.getProductClassifier().getDisplayName();
    }

    private ProductSerial serialOf(String fullNumber) {
        return serialRepository
                .findDetailsByFullNumbers(List.of(fullNumber))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String actorLabelOf(AppUser actor) {

        if (actor == null) {
            return "—";
        }

        String fullName = actor.getFullName();

        return fullName == null || fullName.isBlank()
                ? blankToDash(actor.getUsername())
                : fullName;
    }

    /* ============================================================
       СЛУЖЕБНОЕ
       ============================================================ */

    /**
     * Номер к поиску: только цифры, по которым он и хранится.
     *
     * Пользователь вводит номер со сканера или из документа — с
     * пробелами и дефисами, а в журнале он лежит слитно.
     */
    private static String normalize(String value) {

        if (value == null) {
            return null;
        }

        String digits = value.replaceAll("\\D", "");

        return digits.isEmpty() ? null : digits;
    }

    private static String format(LocalDateTime moment) {
        return moment == null ? "—" : moment.format(DATE_TIME);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
