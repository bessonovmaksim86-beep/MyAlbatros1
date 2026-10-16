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
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialMovementEventRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

        List<PassportView.PassportEventRow> rows =
                new ArrayList<>(history.size());

        for (SerialMovementEvent event : history) {
            rows.add(rowOf(event));
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
                                == ru.company.production.entity
                                .SerialMovementType.QUARANTINED)
                        .count(),
                history.stream()
                        .filter(event -> event.getEventType()
                                == ru.company.production.entity
                                .SerialMovementType.REPAIR_CANCELLED)
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
            SerialMovementEvent event) {

        return new PassportView.PassportEventRow(
                event.getEventType().getDisplayName(),
                event.getEventType().getBadgeClass(),
                format(event.getOccurredAt()),
                actorLabelOf(event.getActor()),
                documentLabelOf(event),
                blankToDash(event.getNote()),
                stateLabelOf(event)
        );
    }

    /**
     * Документ-источник события.
     *
     * Собирают по всем возможным основаниям сразу: у события ремонта
     * есть ремонтный заказ, у входа в производство — заказ-комплектующий,
     * у прихода — приходный ордер. Перечисляют через «·», потому что
     * ремонтный ЗНП и заказ-комплектующий на производство — разные
     * документы одного периода, и смешивать их в одной подписи нельзя.
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

        if (batch != null) {
            documents.add("Приход " + blankToDash(batch.getIncomingOrderNumber())
                    + " · " + blankToDash(batch.getSupplierName()));
        }

        QuarantineTransfer transfer = event.getQuarantineTransfer();

        if (transfer != null) {
            documents.add("Перемещение №" + transfer.getId());
        }

        return documents.isEmpty() ? "—" : String.join(" · ", documents);
    }

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
