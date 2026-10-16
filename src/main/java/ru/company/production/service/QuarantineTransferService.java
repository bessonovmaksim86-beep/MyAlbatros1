package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.InputControlResult;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.QuarantineTransfer;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.QuarantineTransferRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Перемещение забракованных ячеек из изолятора брака на приёмку
 * складу полуфабрикатов.
 *
 * Маршрут: контролёр бюро в поштучном перечне изолятора отмечает
 * ячейки, указывает причину и передаёт их складу; кладовщик открывает
 * свой раздел, сверяет номера с тем, что реально привезли, и принимает
 * на склад. Ячейка, которую склад не принял, возвращается в изолятор —
 * строка перемещения снимается, и перечень изолятора показывает её
 * снова.
 *
 * Состав держат полным номером, а не ссылкой на {@link ProductSerial}:
 * в изолятор попадают и номера партий прихода, ещё не внесённые в
 * реестр (тот же мотив, что у repair_kitting_items — V52), поэтому
 * ссылка на реестр не покрывала бы половину состава изолятора.
 *
 * Причина перемещения обязательна: перемещённая ячейка исчезает из
 * изолятора, и без причины её исчезновение нельзя объяснить ни разбором
 * брака, ни рекламацией поставщику.
 *
 * Приёмку проводит склад, а не бюро: отметка приёмки на ячейке —
 * событие склада, и ставить её за кладовщика бюро не вправе.
 */
@Service
@RequiredArgsConstructor
public class QuarantineTransferService {

    /** Длина полного номера: 4 цифры кода + 5 цифры серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Номеров в сообщении об ошибке: длинный список не читаем. */
    private static final int MESSAGE_NUMBER_LIMIT = 12;

    /** Максимальная длина причины перемещения. */
    private static final int REASON_MAX_LENGTH = 500;

    private final QuarantineTransferRepository transferRepository;
    private final InputControlService inputControlService;
    private final ProductClassifierRepository classifierRepository;
    private final ProductSerialRepository serialRepository;
    private final SerialMovementJournalService journal;

    /* ============================================================
       ПЕРЕЧНИ
       ============================================================ */

    /**
     * Ячейки, которые бюро передало, но склад ещё не принял.
     */
    @Transactional(readOnly = true)
    public List<TransferView> findPending() {
        return transferRepository.findPending().stream()
                .map(TransferView::new)
                .toList();
    }

    /** Перемещённых ячеек, ожидающих приёмки: счётчик в боковом меню склада. */
    @Transactional(readOnly = true)
    public long countPending() {
        return transferRepository.countByAcceptedFalse();
    }

    /**
     * Номера изолятора, уже переданные складу.
     *
     * Перечень изолятора обязан показать их неактивными иначе контролёр
     * отправил бы на приёмку один и тот же номер дважды, а склад
     * провёл бы его дважды.
     */
    @Transactional(readOnly = true)
    public Set<String> findPendingNumbers() {
        return new LinkedHashSet<>(
                transferRepository.findPendingFullNumbers());
    }

    /**
     * Номера, уже принятые складом: изолятор брака их больше не
     * показывает.
     *
     * Принятая ячейка лежит на складе и в изоляторе одновременно быть
     * не может, поэтому её убирают из перечня целиком, а не помечают:
     * иначе контролёр искал бы в изоляторе то, чего там уже нет.
     */
    @Transactional(readOnly = true)
    public Set<String> findAcceptedNumbers() {
        return new LinkedHashSet<>(
                transferRepository.findAcceptedFullNumbers());
    }

    /* ============================================================
       ПЕРЕМЕЩЕНИЕ ИЗ ИЗОЛЯТОРА
       ============================================================ */

    /**
     * Проводит перемещение: отмеченные строки изолятора уходят на
     * приёмку складу полуфабрикатов.
     *
     * Состав берут из изолятора, а не из формы: чекбоксы пользователь
     * не редактирует, но форму можно отправить вручную, и тогда её
     * содержимое — недоверенные данные. Поэтому номер сверяют с
     * актуальным перечнем изолятора по той же паре «группа | номер»,
     * что рисует страница (см. {@code QuarantineCellView.getGroupKey()}).
     *
     * Либо проводится весь отмеченный состав, либо ни одной строки:
     * частичное перемещение оставило бы перечень склада расходящимся с
     * тем, что контролёр видел на экране.
     *
     * Разновидность, классификатор и партию копируют на момент
     * перемещения: стадия движения со временем меняется (подсборка
     * после приёмки складом становится ячейкой), а склад обязан видеть
     * то же, что контролёр видел в изоляторе.
     *
     * @param cellKeys пары «groupId|номер» из чекбоксов перечня
     * @param reason    причина перемещения, обязательна
     */
    @Transactional
    public InputControlResult transfer(List<String> cellKeys,
                                       String reason,
                                       AppUser actor) {

        String reasonText = trimToNull(reason);

        if (reasonText == null) {
            return InputControlResult.rejected(
                    "Укажите причину перемещения: перемещённая ячейка "
                            + "исчезает из изолятора, и без причины её "
                            + "исчезновение нечем объяснить"
            );
        }

        if (reasonText.length() > REASON_MAX_LENGTH) {
            return InputControlResult.rejected(
                    "Причина длиннее " + REASON_MAX_LENGTH + " символов — "
                            + "сократите её"
            );
        }

        List<QuarantineCellView> picked = pickCells(cellKeys);

        if (picked.isEmpty()) {
            return InputControlResult.rejected(
                    "Отметьте в изоляторе хотя бы одну ячейку"
            );
        }

        Set<String> already = findPendingNumbers();

        List<String> waiting = picked.stream()
                .map(QuarantineCellView::getFullNumber)
                .filter(already::contains)
                .toList();

        if (!waiting.isEmpty()) {
            return InputControlResult.rejected(
                    "Эти ячейки уже переданы складу и ждут приёмки: "
                            + join(waiting)
            );
        }

        LocalDateTime moment = LocalDateTime.now();

        /*
         * Классификаторы подтягиваем пакетом, а не на каждую ячейку:
         * open-in-view выключен, fetch на каждую строку упал бы на
         * ленивой связи.
         */
        Map<Long, ProductClassifier> classifiers =
                classifiersOf(picked);

        List<QuarantineTransfer> saved = new ArrayList<>();

        for (QuarantineCellView cell : picked) {
            QuarantineTransfer transfer = new QuarantineTransfer();
            transfer.setFullNumber(cell.getFullNumber());
            transfer.setCellKind(cell.getKind());
            transfer.setReason(reasonText);
            transfer.setTransferredBy(actor);
            transfer.setTransferredAt(moment);
            transfer.setDefectGroup(cell.getDefectGroup());
            transfer.setReceivingBatch(cell.getBatch());
            transfer.setProductClassifier(
                    classifierOf(cell, classifiers)
            );
            saved.add(transfer);
        }

        transferRepository.saveAll(saved);

        journal.recordTransferredToWarehouse(saved, actor, moment);

        return InputControlResult.accepted(
                "Перемещено на приёмку складу: " + saved.size() + " яч. "
                        + "(" + picked.stream()
                        .map(QuarantineCellView::getKindLabel)
                        .distinct()
                        .collect(Collectors.joining(", "))
                        + "). Причина: «" + reasonText + "»"
        );
    }

    /* ============================================================
       ПРИЁМКА СКЛАДОМ
       ============================================================ */

    /**
     * Проводит приёмку переданных ячеек.
     *
     * Номера сверяют с перечнем передачи: кладовщик подтверждает
     * сканером только то, что реально привезли, а всё, что не
     * подтверждено, остаётся ждать приёмки. Отметку ставят и на
     * ячейке реестра ({@code accepted_at}), если она там есть — иначе
     * склад показывал бы принятую ячейку свободной, и её выдали бы
     * в производство повторно.
     *
     * @param scannedNumbers номера, подтверждённые кладовщиком
     */
    @Transactional
    public InputControlResult accept(List<String> scannedNumbers,
                                     AppUser receiver) {

        List<String> scanned = parseNumbers(scannedNumbers);

        if (scanned.isEmpty()) {
            return InputControlResult.rejected(
                    "Подтвердите номера ячеек, которые реально приняты на склад"
            );
        }

        List<QuarantineTransfer> pending = transferRepository.findPending();

        Map<String, List<QuarantineTransfer>> pendingByNumber =
                pending.stream().collect(Collectors.groupingBy(
                        QuarantineTransfer::getFullNumber,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<String> unknown = scanned.stream()
                .filter(number -> !pendingByNumber.containsKey(number))
                .toList();

        if (!unknown.isEmpty()) {
            return InputControlResult.rejected(
                    "Этих ячеек нет среди перемещённых из изолятора: "
                            + join(unknown)
                            + ". Обновите перечень — возможно, их уже приняли"
            );
        }

        LocalDateTime moment = LocalDateTime.now();

        List<QuarantineTransfer> closing = new ArrayList<>();

        for (String number : scanned) {
            pendingByNumber.getOrDefault(number, List.of())
                    .forEach(transfer -> {
                        transfer.setAccepted(true);
                        transfer.setAcceptedBy(receiver);
                        transfer.setAcceptedAt(moment);
                        closing.add(transfer);
                    });
        }

        transferRepository.saveAll(closing);

        List<String> acceptedNumbers =
                closing.stream()
                        .map(QuarantineTransfer::getFullNumber)
                        .distinct()
                        .toList();

        markWarehouseAccepted(acceptedNumbers, receiver, moment);

        return InputControlResult.accepted(
                "Принято на склад из изолятора брака: "
                        + acceptedNumbers.size() + " яч."
        );
    }

    /**
     * Возвращает ячейки обратно в изолятор брака.
     *
     * Строка перемещения снимается, и перечень изолятора показывает
     * ячейку снова: состав изолятора описывают группы брака, поэтому
     * возвращать ничего не нужно — достаточно убрать пометку о том,
     * что ячейка убрана со склада.
     */
    @Transactional
    public InputControlResult returnToQuarantine(List<Long> transferIds,
                                                 AppUser actor) {

        if (transferIds == null || transferIds.isEmpty()) {
            return InputControlResult.rejected(
                    "Отметьте ячейки, которые возвращаете в изолятор"
            );
        }

        List<QuarantineTransfer> transfers =
                transferRepository.findAllById(transferIds);

        if (transfers.isEmpty()) {
            return InputControlResult.rejected(
                    "Перемещение не найдено — обновите перечень"
            );
        }

        /*
         * Отметку журнала ставят до удаления: строку перемещения снимают,
         * и без события возврат выглядел бы как исчезновение ячейки из
         * изолятора. Ссылку на удалённую строку база обнуляет (V65), а
         * реквизиты момента уже скопированы в саму строку журнала.
         */
        journal.recordReturnedToQuarantine(
                transfers, actor, LocalDateTime.now());

        transferRepository.deleteAll(transfers);

        return InputControlResult.accepted(
                "Возвращено в изолятор брака: " + transfers.size() + " яч."
        );
    }

    /* ============================================================
       ВСПОМОГАТЕЛЬНОЕ
       ============================================================ */

    /**
     * Отмечает принятые ячейки на складе в реестре.
     *
     * Реестр может не содержать номера: в изолятор попадают номера
     * партий прихода ещё до сканирования. Поэтому отсутствие строки —
     * не ошибка, а обычный случай, и приёмка от неё не отменяется.
     */
    private void markWarehouseAccepted(List<String> numbers,
                                       AppUser receiver,
                                       LocalDateTime moment) {

        if (numbers.isEmpty()) {
            return;
        }

        List<ProductSerial> serials =
                serialRepository.findDetailsByFullNumbers(numbers);

        if (serials.isEmpty()) {
            return;
        }

        serialRepository.markAcceptedByIds(
                serials.stream().map(ProductSerial::getId).toList(),
                moment,
                receiver
        );

        journal.recordAccepted(serials, receiver, moment);
    }

    /**
     * Отмеченные строки изолятора по ключам формы.
     *
     * Перечень читают целиком: сверка обязана идти по актуальному
     * составу изолятора, а не по тому, что страница отдала раньше —
     * иначе переместили бы ячейку, которую тем временем забрали в
     * ремонт.
     */
    private List<QuarantineCellView> pickCells(List<String> cellKeys) {

        Set<String> wanted = new LinkedHashSet<>();

        if (cellKeys != null) {
            cellKeys.stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::trim)
                    .forEach(wanted::add);
        }

        if (wanted.isEmpty()) {
            return List.of();
        }

        return inputControlService.findQuarantineCells(
                        QuarantineFilter.empty())
                .stream()
                .filter(cell -> wanted.contains(cell.getGroupKey()))
                .toList();
    }

    private Map<Long, ProductClassifier> classifiersOf(
            List<QuarantineCellView> cells) {

        List<Long> ids = cells.stream()
                .map(QuarantineCellView::getClassifierId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        return ids.isEmpty()
                ? Map.of()
                : classifierRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(
                        ProductClassifier::getId, Function.identity()));
    }

    private ProductClassifier classifierOf(
            QuarantineCellView cell,
            Map<Long, ProductClassifier> classifiers) {

        Long id = cell.getClassifierId();

        return id == null ? null : classifiers.get(id);
    }

    /**
     * Разбор подтверждённых номеров.
     *
     * Поле заполняют и с клавиатуры, и сканером: разделители — перевод
     * строки, запятая и точка с запятой, сканер добавляет префиксы вида
     * «CELL:». Оставляют только цифры годной длины.
     */
    private static List<String> parseNumbers(Collection<String> lines) {

        if (lines == null) {
            return List.of();
        }

        Set<String> numbers = new LinkedHashSet<>();

        for (String line : lines) {

            if (line == null) {
                continue;
            }

            for (String part : line.split("[,;\\r\\n]+")) {

                String digits = part.replaceAll("\\D", "");

                if (digits.length() == FULL_NUMBER_LENGTH) {
                    numbers.add(digits);
                }
            }
        }

        return List.copyOf(numbers);
    }

    private static String join(List<String> numbers) {
        return numbers.size() <= MESSAGE_NUMBER_LIMIT
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, MESSAGE_NUMBER_LIMIT))
                + " …";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    /* ============================================================
       СТРОКИ ДЛЯ ЭКРАНА
       ============================================================ */

    /**
     * Строка перечня склада: перемещённая ячейка с её подписями.
     *
     * Связи сущности ленивые, а open-in-view выключен, поэтому
     * подписи читает сам объект {@link QuarantineTransfer}.
     */
    public record TransferView(QuarantineTransfer transfer) {

        public Long getId() {
            return transfer.getId();
        }

        public String getFullNumber() {
            return transfer.getFullNumber();
        }

        public String getKindLabel() {
            return transfer.getCellKindLabel();
        }

        public String getClassifierLabel() {
            return transfer.getClassifierLabel();
        }

        public String getSupplierName() {
            return transfer.getSupplierName();
        }

        public String getIncomingOrderNumber() {
            return transfer.getIncomingOrderNumber();
        }

        public String getReason() {
            return transfer.getReason();
        }

        public String getTransferredByLabel() {
            return transfer.getTransferredByLabel();
        }

        public String getTransferredAtLabel() {
            return transfer.getTransferredAtLabel();
        }
    }
}
