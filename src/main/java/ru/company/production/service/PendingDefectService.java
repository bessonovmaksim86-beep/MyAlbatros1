package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.PendingDefectForm;
import ru.company.production.entity.*;
import ru.company.production.repository.PendingDefectRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.QuarantineTransferRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Заявки на отбраковку ячейки/подсборки с согласованием БВК.
 *
 * Оператор БВК создаёт заявку на отбраковку ячейки/подсборки,
 * находящейся в цеху/отделе. Заявка попадает на согласование
 * представителю БВК (BVK_MASTER или BVK_EXECUTOR), который принимает
 * решение:
 * - подтвердить перемещение в изолятор брака,
 * - отклонить брак и направить ячейку на склад полуфабрикатов.
 *
 * Все реквизиты (разновидность, изделие, партия, местоположение)
 * сервер определяет сам по номеру — оператор вводит только номер,
 * причину и этап отбраковки.
 */
@Service
@RequiredArgsConstructor
public class PendingDefectService {

    private final PendingDefectRepository pendingDefectRepository;
    private final ProductSerialRepository serialRepository;
    private final QuarantineTransferRepository transferRepository;
    private final SerialMovementJournalService journal;
    private final WorkshopScrapService scrapService;

    /**
     * Создать заявку на отбраковку.
     *
     * Проверяет, что номер ещё не имеет ожидающей заявки.
     * Реквизиты вычисляет по номеру: разновидность, изделие, партию,
     * местоположение из журнала перемещений.
     */
    @Transactional
    public PendingDefect create(PendingDefectForm form, AppUser user) {
        String number = normalize(form.getFullNumber());

        if (number == null) {
            throw new IllegalStateException("Номер содержит недопустимые символы");
        }

        // Проверяем, нет ли уже ожидающей заявки на этот номер
        var existing = pendingDefectRepository.findPendingByFullNumber(number);
        if (existing.isPresent()) {
            throw new IllegalStateException(
                    "На номер " + number + " уже есть ожидающая заявка на отбраковку"
            );
        }

        // Получаем серийный номер из реестра
        ProductSerial serial = serialRepository
                .findDetailsByFullNumbers(List.of(number))
                .stream()
                .findFirst()
                .orElse(null);

        // Определяем местоположение и реквизиты
        String location;
        QuarantineCellKind cellKind;
        ProductSerialStatus serialStatus;
        ProductClassifier classifier = null;
        CellReceivingBatch batch = null;

        if (serial != null) {
            // Номер в реестре
            classifier = serial.getProductClassifier();
            batch = serial.getReceivingBatch();
            serialStatus = serial.getStatus();
            cellKind = QuarantineCellKind.ofCellReady(serial.isCellReady());
            location = computeLocationFromSerial(serial);
        } else {
            // Номер вне реестра — смотрим журнал
            List<SerialMovementEvent> history = journal.historyOf(number);
            if (!history.isEmpty()) {
                SerialMovementEvent last = history.get(history.size() - 1);
                classifier = last.getProductClassifier();
                batch = last.getReceivingBatch();
                cellKind = last.getCellKind() != null
                        ? last.getCellKind()
                        : QuarantineCellKind.SUBASSEMBLY;
            } else {
                cellKind = QuarantineCellKind.SUBASSEMBLY;
            }
            serialStatus = null;
            location = computeLocationFromJournal(number);
        }

        PendingDefect defect = new PendingDefect();
        defect.setFullNumber(number);
        defect.setCellKind(cellKind);
        defect.setSerialStatus(serialStatus);
        defect.setProductClassifier(classifier);
        defect.setReceivingBatch(batch);
        defect.setCurrentLocation(location);
        defect.setDefectReason(form.getDefectReason().trim());
        defect.setDefectStage(form.getDefectStage().trim());
        defect.setRequestedBy(user);

        return pendingDefectRepository.save(defect);
    }

    /**
     * Получить все ожидающие решения заявки.
     */
    @Transactional(readOnly = true)
    public List<PendingDefect> findPending() {
        return pendingDefectRepository.findPending();
    }

    /**
     * Получить все решённые заявки.
     */
    @Transactional(readOnly = true)
    public List<PendingDefect> findResolved() {
        return pendingDefectRepository.findResolved();
    }

    /**
     * Получить заявку по ID (для отображения).
     */
    @Transactional(readOnly = true)
    public PendingDefect findById(Long id) {
        return pendingDefectRepository.findById(id).orElse(null);
    }

    /**
     * Количество ожидающих решений — для счётчика в меню.
     */
    @Transactional(readOnly = true)
    public long countPending() {
        return pendingDefectRepository.countByDecisionIsNull();
    }

    /**
     * Проверить, есть ли ожидающая заявка по номеру.
     */
    @Transactional(readOnly = true)
    public boolean hasPendingByNumber(String number) {
        String norm = normalize(number);
        return norm != null && pendingDefectRepository
                .findPendingByFullNumber(norm)
                .isPresent();
    }

    /**
     * Принять решение контроллера по подтверждению дефекта.
     *
     * ОБА варианта кладут ячейку в изолятор брака: подтверждённый дефект
     * — потому что брак нашли, неподтверждённый — потому что цех уже снял
     * её с изделия, и на сборку она вернуться не может. Разница только в
     * подписи для анализа брака, поэтому направление (изолятор) у обоих
     * одно, а {@link DefectConfirmed} отвечает на вопрос «верить ли
     * заявке цеха».
     *
     * Кроме перемещения в изолятор подтверждённая заявка снимает ячейку с
     * комплектования ЗНП: строка связи удаляется, проведённые операции
     * остаются в паспорте, а заказ остаётся на складе недоукомплектованным
     * — по тому же признаку склад ведёт счётчик доукомплектования.
     *
     * @param defectId ID заявки
     * @param decision CONFIRMED либо DENIED
     * @param comment  комментарий контроллера
     * @param user     представитель бюро, принимающий решение
     */
    @Transactional
    public PendingDefect applyDefectDecision(Long defectId,
                                             DefectConfirmed decision,
                                             String comment,
                                             AppUser user) {
        PendingDefect defect = pendingDefectRepository.findById(defectId)
                .orElseThrow(() -> new IllegalStateException(
                        "Заявка на отбраковку не найдена: " + defectId
                ));

        if (!defect.isPending()) {
            throw new IllegalStateException(
                    "Заявка уже решена: " + defect.getFullNumber()
            );
        }

        LocalDateTime now = LocalDateTime.now();

        defect.setDefectConfirmed(decision);
        defect.setControllerComment(comment == null || comment.isBlank()
                ? null
                : comment.trim());
        defect.setDecision(PendingDefectDecision.APPROVED_TO_QUARANTINE);
        defect.setApprovedBy(user);
        defect.setApprovedAt(now);

        PendingDefect saved = pendingDefectRepository.save(defect);

        moveToQuarantine(saved, user, now);

        /*
         * Снятие ЗНП — домен цеха и склада, поэтому живёт в
         * WorkshopScrapService: там известны связи матрицы комплектования
         * и порядок сохранения истории операций.
         */
        scrapService.releaseZnpQuota(saved, user);

        return saved;
    }

    /**
     * Переместить в изолятор брака.
     *
     * Создаёт строку перемещения и записывает событие журнала. Причина
     * несёт и дефект, и вывод контроллера: паспорт ячейки обязан показать
     * оба, иначе решение «дефект не подтверждён» выглядело бы как
     * перемещение без основания.
     */
    private void moveToQuarantine(PendingDefect defect,
                                  AppUser user,
                                  LocalDateTime moment) {

        String reason = defectReasonLabel(defect);

        QuarantineTransfer transfer = new QuarantineTransfer();
        transfer.setFullNumber(defect.getFullNumber());
        transfer.setCellKind(defect.getCellKind());
        transfer.setProductClassifier(defect.getProductClassifier());
        transfer.setReceivingBatch(defect.getReceivingBatch());
        transfer.setReason(reason);
        transfer.setTransferredBy(user);
        transfer.setTransferredAt(moment);
        transfer.setAccepted(false);

        transferRepository.save(transfer);

        journal.recordQuarantinedByRequest(
                defect.getFullNumber(),
                defect.getCellKind(),
                defect.getSerialStatus(),
                defect.getProductClassifier(),
                defect.getReceivingBatch(),
                user,
                moment,
                reason
        );
    }

    /**
     * Причина для журнала и перемещения: дефект плюс вывод контроллера.
     */
    private static String defectReasonLabel(PendingDefect defect) {
        StringBuilder label = new StringBuilder();

        if (defect.getDefectConfirmed() != null) {
            label.append(defect.getDefectConfirmed().getDisplayName())
                    .append(". ");
        }

        label.append(defect.getDefectReason());

        if (defect.getControllerComment() != null) {
            label.append(" · ").append(defect.getControllerComment());
        }

        return label.toString();
    }

    /**
     * Вычислить текущее местоположение номера из журнала перемещений.
     */
    private String computeLocationFromJournal(String fullNumber) {
        List<SerialMovementEvent> history = journal.historyOf(fullNumber);

        if (history.isEmpty()) {
            return "Неизвестно";
        }

        SerialMovementEvent last = history.get(history.size() - 1);
        SerialMovementType type = last.getEventType();

        return switch (type) {
            case QUARANTINED -> "Изолятор брака";
            case ACCEPTED, TRANSFERRED_TO_WAREHOUSE -> "Склад СПФ";
            case IN_PRODUCTION -> "Цех (выпуск)";
            case REPAIR_ORDERED, KITTED_TO_REPAIR_ZNP -> "Ремонт";
            case RECEIVED, ISSUED -> "Склад";
            case RETURNED_TO_SUPPLIER -> "Возврат поставщику";
            default -> "Неизвестно";
        };
    }

    /**
     * Вычислить местоположение по серийному номеру.
     */
    private String computeLocationFromSerial(ProductSerial serial) {
        if (serial.isAccepted()) {
            return "Склад СПФ";
        }
        if (serial.getIssuedProductionOrder() != null) {
            return "Цех (выпуск)";
        }
        return "Склад";
    }

    /**
     * Номер к поиску: только цифры, по которым он и хранится.
     */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }
}
