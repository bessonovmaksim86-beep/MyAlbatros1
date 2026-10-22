package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.DeletionGroup;
import ru.company.production.dto.DeletionGroupKind;
import ru.company.production.dto.DeletionPreview;
import ru.company.production.dto.DeletionWarning;
import ru.company.production.dto.DeletionWarningLevel;
import ru.company.production.entity.AdminDeletion;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.DeletionAction;
import ru.company.production.entity.DeletionTargetKind;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.repository.AdminDeletionRepository;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.InputControlCardCellRepository;
import ru.company.production.repository.InputControlCardReasonRepository;
import ru.company.production.repository.InputControlCardRepository;
import ru.company.production.repository.InputControlDefectGroupRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionItemLinkRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.QuarantineTransferRepository;
import ru.company.production.repository.RepairKittingItemRepository;
import ru.company.production.repository.RepairOrderItemRepository;
import ru.company.production.repository.SerialMovementEventRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Раздел «Очистка данных»: жёсткое удаление заказов и откат прихода.
 *
 * Диспетчер удаляет заказ мягко ({@code active = false}), и этого
 * достаточно, пока запись ошибочна. Раздел нужен для другого случая:
 * запись заведена неверно настолько, что её следы в матрице
 * комплектования, графике и реестре мешают работе, — либо удаление
 * уже произошло в обход системы и его последствия надо собрать в
 * порядок. Здесь строки исчезают физически.
 *
 * ПОЧЕМУ ДВА ШАГА, А НЕ ОДНА КНОПКА. Заказ связан с семью таблицами,
 * часть строк в которых создавали другие роли: цех закрывал операции,
 * склад принимал ячейки, БВК проводило контроль. Одно действие,
 * затрагивающее чужие отметки, обязано быть показанным до выполнения,
 * иначе администратор узнаёт о последствиях тогда, когда отменить их
 * уже нельзя. Первый шаг читает и перечисляет, второй выполняет ровно
 * перечисленное — и сверяет состав по подписи, чтобы изменённая в
 * браузере форма не удалила лишнее.
 *
 * ПОЧЕМУ УДАЛЕНИЕ ИДЁТ ЗАПРОСАМИ, А НЕ КАСКАДОМ СХЕМЫ. Внешние ключи
 * разбросаны по миграциям V16–V65 unevenly: часть с CASCADE, часть с
 * RESTRICT, часть с SET NULL. Предоставить удаление каскадам означало
 * бы, что исход зависит от того, где остановился MySQL, и часть
 * связанных строк осталась бы висеть. Поэтому порядок обхода задан
 * здесь явно — от зависимых строк к записи, — а запросы на отвязку
 * там, где строку нужно сохранить (журнал перемещений переживает
 * удалённый заказ, позиция ремонта переживает удалённую группу брака).
 *
 * Каждый проход записывается в {@link AdminDeletion}: физическое
 * удаление не оставляет следа в самих таблицах, и без журнала нельзя
 * ответить, кто вернул базу в это состояние.
 */
@Service
@RequiredArgsConstructor
public class AdminDataCleanupService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductSerialRepository productSerialRepository;
    private final ProductSerialOperationRepository serialOperationRepository;
    private final SerialProductionOrderRepository kittingLinkRepository;
    private final ProductionItemLinkRepository itemLinkRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final RepairKittingItemRepository repairKittingItemRepository;
    private final RepairOrderItemRepository repairOrderItemRepository;
    private final SerialMovementEventRepository movementEventRepository;
    private final CellReceivingBatchRepository batchRepository;
    private final InputControlCardRepository inputControlCardRepository;
    private final InputControlCardCellRepository inputControlCardCellRepository;
    private final InputControlCardReasonRepository inputControlCardReasonRepository;
    private final InputControlDefectGroupRepository defectGroupRepository;
    private final QuarantineTransferRepository quarantineTransferRepository;
    private final AdminDeletionRepository deletionRepository;

    /* ================================================================
     * Предпросмотр: заказ на производство
     * ================================================================ */

    /**
     * Что уйдёт вместе с заказом и чем это грозит.
     *
     * Считается по всему поддереву: выпуск удаляется вместе со
     * вложенными ЗНП (ключ parent_production_order_id с RESTRICT, и
     * оставить их нельзя), поэтому цифры предпросмотра обязаны
     * относиться ко всему составу, а не к одной строке заказа.
     */
    @Transactional(readOnly = true)
    public DeletionPreview previewOrder(Long orderId) {

        ProductionOrder order = requireOrder(orderId);

        List<ProductionOrder> subtree = orderSubtree(orderId);
        List<Long> orderIds = subtree.stream().map(ProductionOrder::getId).toList();

        List<DeletionGroup> groups = new ArrayList<>();
        List<DeletionWarning> warnings = new ArrayList<>();

        /* --- Вложенные заказы-комплектующие. --- */

        List<ProductionOrder> children = subtree.stream()
                .filter(candidate -> !candidate.getId().equals(orderId))
                .toList();

        groups.add(new DeletionGroup(
                DeletionGroupKind.NESTED_ZNP,
                children.size(),
                children.stream().map(ProductionOrder::getNumber).toList()
        ));

        /* --- Позиции выпуска. --- */

        groups.add(new DeletionGroup(
                DeletionGroupKind.ORDER_ITEMS,
                orderIds.stream()
                        .mapToLong(productionOrderItemRepository::countByOrderId)
                        .sum(),
                List.of()
        ));

        /* --- Серийные номера выпуска. --- */

        List<ProductSerial> serials = orderIds.isEmpty()
                ? List.of()
                : productSerialRepository.findByProductionOrderIds(orderIds);

        List<Long> serialIds = serials.stream()
                .map(ProductSerial::getId)
                .filter(Objects::nonNull)
                .toList();

        groups.add(new DeletionGroup(
                DeletionGroupKind.RELEASE_SERIALS,
                serials.size(),
                serials.stream().map(ProductSerial::getFullNumber).toList()
        ));

        /* --- Операции по изделиям: план и факт отдельными группами. --- */

        long planned = orderIds.stream()
                .mapToLong(serialOperationRepository::countPlannedByProductionOrderId)
                .sum();

        long performed = orderIds.stream()
                .mapToLong(serialOperationRepository::countPerformedByProductionOrderId)
                .sum();

        groups.add(new DeletionGroup(
                DeletionGroupKind.PLANNED_OPERATIONS, planned, List.of()
        ));

        groups.add(new DeletionGroup(
                DeletionGroupKind.PERFORMED_OPERATIONS, performed, List.of()
        ));

        if (performed > 0) {
            warnings.add(new DeletionWarning(
                    DeletionWarningLevel.ATTENTION,
                    "По заказу проведены фактические работы: " + performed + " стр.",
                    "Закрытые операции"
                            + namesSuffix(serialOperationRepository
                                    .findPerformedOperationNamesByProductionOrderId(orderId))
                            + " — отметки цеха о выполненной работе. Их удаление "
                            + "неотменимо: восстановит их только повторная "
                            + "отметка исполнителя."
            ));
        }

        /* --- График работ. --- */

        long scheduleRows = orderIds.stream()
                .mapToLong(scheduleRowRepository::countByProductionOrder_Id)
                .sum();

        long completedRows = orderIds.stream()
                .mapToLong(scheduleRowRepository::countCompletedByProductionOrderId)
                .sum();

        groups.add(new DeletionGroup(
                DeletionGroupKind.SCHEDULE_ROWS, scheduleRows, List.of()
        ));

        if (completedRows > 0) {
            warnings.add(new DeletionWarning(
                    DeletionWarningLevel.ATTENTION,
                    "В графике есть закрытые строки: " + completedRows + " шт.",
                    "Закрытая строка графика — отметка, что этап пройден. "
                            + "Ключ графика удалится вместе с заказом каскадом "
                            + "(V56), и вернуть её будет нечем."
            ));
        }

        /* --- Состав комплектования и возврат ячеек на склад. --- */

        long kittingLinks = orderIds.stream()
                .mapToLong(kittingLinkRepository::countByProductionOrder_Id)
                .sum();

        List<Long> kittedSerialIds = orderIds.stream()
                .flatMap(id -> kittingLinkRepository.findSerialIdsByProductionOrderId(id).stream())
                .distinct()
                .toList();

        groups.add(new DeletionGroup(
                DeletionGroupKind.KITTING_LINKS,
                kittingLinks,
                kittedSerialIds.isEmpty()
                        ? List.of()
                        : productSerialRepository
                                .findAllById(kittedSerialIds).stream()
                                .map(ProductSerial::getFullNumber)
                                .toList()
        ));

        if (!kittedSerialIds.isEmpty()) {
            long onWarehouse = productSerialRepository
                    .findAllById(kittedSerialIds).stream()
                    .filter(ProductSerial::isAccepted)
                    .count();

            warnings.add(new DeletionWarning(
                    DeletionWarningLevel.INFO,
                    "Ячейки состава вернутся на склад: " + kittedSerialIds.size() + " шт.",
                    onWarehouse == 0
                            ? "Связь комплектования снимется, изделия снова попадут "
                              + "в перечень свободных на складе полуфабрикатов."
                            : "Принятых складом ячеек — " + onWarehouse
                              + "; отметка приёмки сохраняется, именно она означает "
                              + "«лежит на складе»."
            ));
        }

        /* --- Комплектование ремонтных заказов. --- */

        groups.add(new DeletionGroup(
                DeletionGroupKind.REPAIR_KITTING_ITEMS,
                orderIds.stream()
                        .mapToLong(id -> repairKittingItemRepository
                                .findByOrderId(id).size())
                        .sum(),
                List.of()
        ));

        /* --- Журнал перемещений. --- */

        long journalByOrder = orderIds.stream()
                .mapToLong(movementEventRepository::countByProductionOrderId)
                .sum();

        long journalBySerials = serialIds.isEmpty()
                ? 0
                : movementEventRepository.countBySerialIdIn(serialIds);

        groups.add(new DeletionGroup(
                DeletionGroupKind.MOVEMENT_JOURNAL,
                journalByOrder + journalBySerials,
                List.of()
        ));

        /* --- Что удерживает удаление. --- */

        warnings.addAll(orderBlockers(subtree, serials, serialIds));

        return preview(
                DeletionTargetKind.PRODUCTION_ORDER,
                orderId,
                order.getNumber(),
                orderSubtitle(order),
                groups,
                warnings,
                DeletionAction.HARD_DELETE
        );
    }

    /**
     * Причина, по которой заказ удалять нельзя.
     *
     * Единственный случай, где подтверждение не помогает, — серийник
     * удаляемого выпуска стоит в связи экземпляров
     * ({@code production_item_links}): он вставлен в собранное изделие,
     * у которого свой заказ и своя прослеживаемость. Ключ связи
     * висит на реестре с ON DELETE CASCADE (V17), поэтому удаление
     * номера молча вынешь датчик из чужого прибора. Такое лечится только
     * порядком удаления — сначала выпуск, внутри которого стоит изделие,
     * — а не отметкой «удалять всё».
     */
    private List<DeletionWarning> orderBlockers(
            List<ProductionOrder> subtree,
            List<ProductSerial> serials,
            List<Long> serialIds) {

        List<DeletionWarning> blockers = new ArrayList<>();

        if (!serialIds.isEmpty()) {
            var links = itemLinkRepository.findBySerialIdIn(serialIds);

            if (!links.isEmpty()) {

                /*
                 * Номера собираются из уже прочитанных связей: второй
                 * запрос на каждый номер дал бы N обращений к базе ради
                 * текста предупреждения.
                 */
                Set<Long> linkedIds = links.stream()
                        .flatMap(link -> java.util.stream.Stream.of(
                                link.getParentSerial().getId(),
                                link.getChildSerial().getId()))
                        .filter(serialIds::contains)
                        .collect(Collectors.toSet());

                List<String> assembled = serials.stream()
                        .filter(serial -> linkedIds.contains(serial.getId()))
                        .map(ProductSerial::getFullNumber)
                        .toList();

                blockers.add(DeletionWarning.ofGroup(
                        DeletionGroupKind.RELEASE_SERIALS,
                        "Номера выпущенного изделия вставлены в сборку: "
                                + links.size() + " связ.",
                        "Номера " + join(assembled) + " входят в состав других "
                                + "изделий. Удаление этих номеров вынет их оттуда "
                                + "каскадом и оставит собранное изделие без "
                                + "состава — снимите отметку с серийных номеров "
                                + "либо сначала удалите заказ того изделия, в "
                                + "которое они установлены."
                ));
            }
        }

        boolean anyDone = subtree.stream()
                .anyMatch(candidate -> candidate.getStatus()
                        == ProductionOrderStatus.DONE
                        || candidate.getStatus()
                        == ProductionOrderStatus.CANCELLED);

        if (anyDone) {
            blockers.add(new DeletionWarning(
                    DeletionWarningLevel.ATTENTION,
                    "В поддереве есть закрытые заказы",
                    "Заказ в статусе «Выполнен» или «Отменён» — запись истории: "
                            + "его удаляют, только когда выпуск проводили по ошибке "
                            + "и его строки мешают работе остальных разделов."
            ));
        }

        return blockers;
    }

    /* ================================================================
     * Предпросмотр: партия оприходования
     * ================================================================ */

    /**
     * Что даст откат оприходования.
     *
     * Откат — не удаление прихода по умолчанию: партия возвращается в
     * черновик, и приход можно провести заново, например после ошибки
     * сканирования. Сама заявка прихода удаляет только отмеченная
     * группа {@link DeletionGroupKind#BATCH_HEADER}, и по умолчанию
     * она снята ровно поэтому.
     */
    @Transactional(readOnly = true)
    public DeletionPreview previewBatch(Long batchId) {

        CellReceivingBatch batch = requireBatch(batchId);

        List<ProductSerial> serials =
                productSerialRepository.findDetailsByReceivingBatchId(batchId);

        List<Long> serialIds = serials.stream()
                .map(ProductSerial::getId)
                .filter(Objects::nonNull)
                .toList();

        List<DeletionGroup> groups = new ArrayList<>();
        List<DeletionWarning> warnings = new ArrayList<>();

        groups.add(new DeletionGroup(
                DeletionGroupKind.BATCH_SERIALS,
                serials.size(),
                serials.stream().map(ProductSerial::getFullNumber).toList()
        ));

        groups.add(new DeletionGroup(
                DeletionGroupKind.MOVEMENT_JOURNAL,
                movementEventRepository.countByReceivingBatchId(batchId)
                        + (serialIds.isEmpty()
                                ? 0
                                : movementEventRepository.countBySerialIdIn(serialIds)),
                List.of()
        ));

        long cards = inputControlCardRepository.countByBatch_Id(batchId);

        groups.add(new DeletionGroup(
                DeletionGroupKind.INPUT_CONTROL_CARDS, cards, List.of()
        ));

        long transfers = quarantineTransferRepository.countByReceivingBatch_Id(batchId);

        groups.add(new DeletionGroup(
                DeletionGroupKind.QUARANTINE_TRANSFERS, transfers, List.of()
        ));

        groups.add(new DeletionGroup(
                DeletionGroupKind.BATCH_HEADER, 1, List.of()
        ));

        /* --- Напечатанные этикетки: серия станет свободной. --- */

        if (batch.isQrPrinted()) {
            warnings.add(new DeletionWarning(
                    DeletionWarningLevel.ATTENTION,
                    "Этикетки партии переданы в печать "
                            + batch.getFormattedQrPrintedAt(),
                    "После отката ряд нумерации " + batch.getCellLabel()
                            + " освобождается: следующий приход получит те же "
                            + "номера " + batch.getNumberRangeLabel() + ". Если "
                            + "часть этикеток уже наклеена, на складе останутся "
                            + "изделия с номерами, которых в реестре нет."
            ));
        }

        if (cards > 0) {
            warnings.add(new DeletionWarning(
                    DeletionWarningLevel.ATTENTION,
                    "Партию проверяло БВК: карточек — " + cards,
                    "Карточки входного контроля, перечень ячеек и причины брака "
                            + "удалются вместе с партией; бюро потеряет результат "
                            + "проверки."
            ));
        }

        if (transfers > 0) {
            warnings.add(new DeletionWarning(
                    DeletionWarningLevel.ATTENTION,
                    "Ячейки партии передавали с склада в изолятор: " + transfers,
                    "Строки перемещения удаляются — изолятор потеряет, откуда "
                            + "взялись эти номера."
            ));
        }

        warnings.addAll(batchBlockers(serials, serialIds));

        return preview(
                DeletionTargetKind.RECEIVING_BATCH,
                batchId,
                batchLabel(batch),
                batchSubtitle(batch),
                groups,
                warnings,
                DeletionAction.RECEIVING_ROLLBACK
        );
    }

    /**
     * Почему номера партии нельзя убрать из реестра.
     *
     * Номер перестал быть «просто внесённым приходом», как только
     * попал в комплектование, в сборку или на склад: с этого момента
     * о нём знают другие разделы. Откат прихода оставляет такие
     * номера в реестре (партию при этом можно вернуть в черновик,
     * отвязав номера) — но удалять их нельзя, иначе матрица
     * комплектования и паспорт номера потеряют изделие.
     */
    private List<DeletionWarning> batchBlockers(
            List<ProductSerial> serials,
            List<Long> serialIds) {

        List<DeletionWarning> blockers = new ArrayList<>();

        if (serialIds.isEmpty()) {
            return blockers;
        }

        long kitted = serialIds.stream()
                .mapToLong(kittingLinkRepository::countBySerial_Id)
                .sum();

        long links = itemLinkRepository.countBySerialIdIn(serialIds);

        long accepted = serials.stream().filter(ProductSerial::isAccepted).count();

        long operations = serialOperationRepository.countBySerialIdIn(serialIds);

        if (kitted > 0 || links > 0 || accepted > 0 || operations > 0) {
            blockers.add(DeletionWarning.ofGroup(
                    DeletionGroupKind.BATCH_SERIALS,
                    "Номера партии уже задействованы",
                    "Внесено в заказы-комплектующие: " + kitted
                            + "; связей сборки: " + links
                            + "; принято на склад: " + accepted
                            + "; операций по изделиям: " + operations
                            + ". Удалить реестр этих номеров нельзя — сначала "
                            + "удалите заказы, куда они вошли, и верните изделия "
                            + "на склад. Откат прихода без удаления номеров "
                            + "доступен: он отвязывает партию, оставив реестр."
            ));
        }

        return blockers;
    }

    /* ================================================================
     * Выполнение
     * ================================================================ */

    /**
     * Удаляет заказ на производство со всем отмеченным составом.
     *
     * Состав сверяется с подписью предпросмотра: форма возвращает
     * набор групп строками, и выполнить удаление с набором, которого
     * администратор не видел, — значит удалить лишнее. Отдельная
     * отметка подтверждения обязательна, если предпросмотр показал
     * предупреждение уровня ATTENTION.
     */
    @Transactional
    public String deleteOrder(Long orderId,
                              Set<DeletionGroupKind> groups,
                              boolean confirmed,
                              String token,
                              String note,
                              AppUser actor,
                              String impersonatedBy) {

        DeletionPreview preview = previewOrder(orderId);

        requireConfirmed(preview, groups, token, confirmed);

        ProductionOrder order = requireOrder(orderId);
        String number = order.getNumber();

        List<ProductionOrder> subtree = orderSubtree(orderId);
        List<Long> orderIds = subtree.stream().map(ProductionOrder::getId).toList();

        boolean deleteSerials = groups.contains(DeletionGroupKind.RELEASE_SERIALS);

        List<Long> serialIds = productSerialRepository
                .findByProductionOrderIds(orderIds).stream()
                .map(ProductSerial::getId)
                .filter(Objects::nonNull)
                .toList();

        /*
         * Журнал отвязывается первым: его ключи на заказ и на номера
         * висят с RESTRICT (V42), и любое удаление ниже упёрлось бы в
         * них. Строки остаются — событие о пройденном номером пути
         * правдиво и без заказа.
         */
        for (Long id : orderIds) {
            movementEventRepository.detachProductionOrder(id);
        }

        if (deleteSerials && !serialIds.isEmpty()) {
            movementEventRepository.deleteBySerialIdIn(serialIds);
            itemLinkRepository.deleteAll(
                    itemLinkRepository.findBySerialIdIn(serialIds));
            productionOrderItemRepository.detachSerials(serialIds);
            kittingLinkRepository.deleteBySerialIdIn(serialIds);
        }

        if (groups.contains(DeletionGroupKind.REPAIR_KITTING_ITEMS)) {
            repairKittingItemRepository.deleteAllByOrderId(orderId);

            orderIds.forEach(id ->
                    repairKittingItemRepository.deleteAllByOrderId(id));
        }

        if (groups.contains(DeletionGroupKind.SCHEDULE_ROWS)) {
            orderIds.forEach(scheduleRowRepository::deleteAllByProductionOrderId);
        }

        if (groups.contains(DeletionGroupKind.PLANNED_OPERATIONS)
                || groups.contains(DeletionGroupKind.PERFORMED_OPERATIONS)) {

            orderIds.forEach(id -> {
                if (groups.contains(DeletionGroupKind.PERFORMED_OPERATIONS)) {
                    serialOperationRepository.deleteAllByProductionOrderId(id);
                } else {
                    serialOperationRepository.deletePlannedByProductionOrderId(id);
                }
            });
        }

        if (groups.contains(DeletionGroupKind.KITTING_LINKS)) {
            orderIds.forEach(kittingLinkRepository::deleteByProductionOrderId);
        }

        if (deleteSerials && !serialIds.isEmpty()) {
            productSerialRepository.deleteAllByIdInBatch(serialIds);
        } else {
            orderIds.forEach(productSerialRepository::detachProductionOrder);
        }

        orderIds.forEach(productionOrderItemRepository::deleteAllByOrderId);

        /*
         * Обход дерева в обратном порядке: ключ parent_production_order_id
         * висит на заказе с RESTRICT (V21), поэтому вложенный ЗНП обязан
         * уйти раньше выпуска. Порядок списка — обход в ширину, где
         * родитель всегда раньше потомка, и разворот списка даёт
         * корректный порядок удаления для дерева любой глубины.
         */
        List<Long> removalOrder = new ArrayList<>(orderIds);

        java.util.Collections.reverse(removalOrder);

        productionOrderRepository.deleteAllByIdInBatch(removalOrder);

        record(preview, note, actor, impersonatedBy);

        return number;
    }

    /**
     * Откатывает оприходование партии.
     *
     * Без {@link DeletionGroupKind#BATCH_HEADER} приход возвращается в
     * черновик: заявка, напечатанные этикетки и диапазон номеров
     * остаются, а реестр и отметки склада очищаются — так проводят
     * пересканирование после ошибки оператора. С отмеченной группой
     * удаляется и сама заявка прихода.
     */
    @Transactional
    public String rollbackBatch(Long batchId,
                                Set<DeletionGroupKind> groups,
                                boolean confirmed,
                                String token,
                                String note,
                                AppUser actor,
                                String impersonatedBy) {

        DeletionPreview preview = previewBatch(batchId);

        requireConfirmed(preview, groups, token, confirmed);

        CellReceivingBatch batch = requireBatch(batchId);
        String label = batchLabel(batch);

        boolean removeHeader = groups.contains(DeletionGroupKind.BATCH_HEADER);
        boolean removeSerials = groups.contains(DeletionGroupKind.BATCH_SERIALS);

        List<Long> serialIds = productSerialRepository
                .findDetailsByReceivingBatchId(batchId).stream()
                .map(ProductSerial::getId)
                .filter(Objects::nonNull)
                .toList();

        if (removeSerials && !serialIds.isEmpty()) {
            movementEventRepository.deleteBySerialIdIn(serialIds);
            productSerialRepository.deleteAllByIdInBatch(serialIds);
        } else {
            productSerialRepository.detachReceivingBatch(batchId);
            movementEventRepository.detachReceivingBatch(batchId);
        }

        if (groups.contains(DeletionGroupKind.INPUT_CONTROL_CARDS)) {
            deleteInputControlCards(batchId);
        }

        if (groups.contains(DeletionGroupKind.QUARANTINE_TRANSFERS)) {
            quarantineTransferRepository.deleteByBatchId(batchId);
        }

        if (removeHeader) {
            /*
             * Оставшиеся ссылки на партию снимаются перед удалением
             * шапки: ключи карточек ВК и перемещений с RESTRICT, и
             * снять их надо даже если соответствующие группы не
             * отмечены — иначе удаление шапки откатит всю транзакцию.
             */
            List<Long> cardIds = inputControlCardRepository
                    .findAllByBatchId(batchId).stream()
                    .map(card -> card.getId())
                    .toList();

            if (!cardIds.isEmpty()) {
                deleteInputControlCards(batchId);
            }

            quarantineTransferRepository.detachBatch(batchId);
            batchRepository.deleteById(batchId);
        } else {
            CellReceivingBatch fresh = requireBatch(batchId);

            fresh.setConfirmedAt(null);
            fresh.setAcceptedAt(null);
            fresh.setAcceptedBy(null);
            fresh.setAcceptedQuantity(null);

            batchRepository.save(fresh);
        }

        record(preview, note, actor, impersonatedBy);

        return label;
    }

    /**
     * Удаляет карточки входного контроля партии вместе с составом.
     *
     * Порядок жёсткий: причины и ячейки ссылаются на карточку с
     * RESTRICT (V29/V31), группы брака — на карточку тоже, а на группы
     * уже смотрят позиции ремонтных заказов (V34). Поэтому сначала
     * отвязываются позиции ремонта, затем уходят причины, ячейки и
     * группы, и только потом — карточки.
     */
    private void deleteInputControlCards(Long batchId) {

        List<Long> cardIds = inputControlCardRepository.findAllByBatchId(batchId)
                .stream()
                .map(card -> card.getId())
                .toList();

        if (cardIds.isEmpty()) {
            return;
        }

        List<Long> groupIds = defectGroupRepository.findAllByCardIds(cardIds)
                .stream()
                .map(group -> group.getId())
                .toList();

        if (!groupIds.isEmpty()) {
            repairOrderItemRepository.detachDefectGroups(groupIds);
        }

        inputControlCardReasonRepository.deleteAllByCardIds(cardIds);
        inputControlCardCellRepository.deleteAllByCardIds(cardIds);

        if (!groupIds.isEmpty()) {
            defectGroupRepository.deleteAllByCardIds(cardIds);
        }

        inputControlCardRepository.deleteAllByIdInBatch(cardIds);
    }

    /* ================================================================
     * Журнал и проверка подтверждения
     * ================================================================ */

    /** Лента удалений — свежие сверху. */
    @Transactional(readOnly = true)
    public List<AdminDeletion> history() {
        return deletionRepository.findAllByOrderByPerformedAtDescIdDesc();
    }

    public long historyCount() {
        return deletionRepository.count();
    }

    /**
     * Проверяет, что удаляют ровно то, что показали, и что с этим
     * согласились.
     *
     * Подпись считается от типа, идентификатора и ВСЕХ показанных
     * групп, а не от отмечаемых: выбор состава — то, ради чего экран
     * предпросмотра существует, и подпись, которая ломается от снятого
     * чекбокса, заставила бы администратора удалять всегда всё. Поэтому
     * подпись отвечает на вопрос «ту ли запись и тот ли перечень
     * показывали», а проверка на включение — на вопрос «не проскочило ли
     * то, чего не показывали вовсе».
     */
    private void requireConfirmed(DeletionPreview preview,
                                  Set<DeletionGroupKind> groups,
                                  String token,
                                  boolean confirmed) {

        if (preview.isBlocked()) {
            DeletionWarning blocker = preview.warningsOf(
                            DeletionWarningLevel.BLOCKING).stream()
                    .filter(DeletionWarning::isGlobalBlock)
                    .findFirst()
                    .orElseThrow();

            throw new IllegalStateException(blocker.title() + ": " + blocker.detail());
        }

        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не отмечено ни одной группы — удалять нечего"
            );
        }

        if (!signature(preview.kind(), preview.id(), shownGroups(preview))
                .equals(token)) {
            throw new IllegalArgumentException(
                    "Предпросмотр устарел или относится к другой записи — "
                            + "откройте перечень заново"
            );
        }

        List<DeletionGroupKind> unknown = groups.stream()
                .filter(kind -> !shownGroups(preview).contains(kind))
                .toList();

        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "В перечень попали группы, которых не было в предпросмотре: "
                            + unknown.stream().map(DeletionGroupKind::getTitle)
                                    .collect(Collectors.joining(", "))
            );
        }

        /*
         * Запрет отдельной группы проверяется здесь, а не на экране:
         * форма позволяет снять отметку с недоступного чекбокса только
         * правкой запроса, и молча проигнорировать такую отметку
         * означало бы удалить то, о чём администратора предупредили.
         */
        Set<DeletionGroupKind> blocked = preview.blockedGroups();

        groups.stream()
                .filter(blocked::contains)
                .findFirst()
                .ifPresent(kind -> {
                    throw new IllegalStateException(
                            "Группу «" + kind.getTitle() + "» удалить нельзя: "
                                    + preview.blockerFor(kind).detail()
                    );
                });

        if (preview.requiresConfirmation() && !confirmed) {
            throw new IllegalArgumentException(
                    "Удаление затронет отметки других подразделений: "
                            + "отметьте подтверждение"
            );
        }

        /* Обязательные группы не снимаются — проверка формы их не вернёт. */
        preview.mandatoryGroups().forEach(group -> {
            if (!groups.contains(group.kind())) {
                throw new IllegalArgumentException(
                        "Группу «" + group.kind().getTitle()
                                + "» нельзя снять: без неё удаление упрётся "
                                + "во внешний ключ"
                );
            }
        });
    }

    /** Группы, которые реально показаны на экране предпросмотра. */
    private static Set<DeletionGroupKind> shownGroups(DeletionPreview preview) {

        Set<DeletionGroupKind> shown =
                EnumSet.noneOf(DeletionGroupKind.class);

        preview.groups().stream()
                .filter(group -> !group.isEmpty())
                .forEach(group -> shown.add(group.kind()));

        return shown;
    }

    private void record(DeletionPreview preview,
                        String note,
                        AppUser actor,
                        String impersonatedBy) {

        AdminDeletion deletion = new AdminDeletion();

        deletion.setTargetKind(preview.kind());
        deletion.setTargetId(preview.id());
        deletion.setTargetLabel(shorten(preview.label(), 200));
        deletion.setAction(preview.action());
        deletion.setAffectedCounts(shorten(preview.affectedCountsLabel(), 2000));
        deletion.setNote(shorten(trimToNull(note), 2000));
        deletion.setImpersonatedBy(shorten(impersonatedBy, 255));
        deletion.setActorId(actor == null ? null : actor.getId());
        deletion.setActorName(actor == null
                ? "Система"
                : shorten(actor.getFullName() == null
                        ? actor.getUsername()
                        : actor.getFullName(), 255));
        deletion.setPerformedAt(LocalDateTime.now());

        deletionRepository.save(deletion);
    }

    /** Подпись показанного состава. */
    public String signature(DeletionTargetKind kind,
                            Long id,
                            Set<DeletionGroupKind> groups) {

        String names = new TreeSet<>(groups).stream()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");

        /*
         * Тип и набор групп возвращаются в форму скрытыми полями, и
         * подпись обязана быть непрогнозируемой: без неё достаточно
         * поменять id в адресной строке, чтобы удалить другую запись с
         * чужого предпросмотра.
         */
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(
                    (kind.name() + "|" + id + "|" + names)
                            .getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Набор групп, отмечаемых по умолчанию, — для формы предпросмотра.
     *
     * Заблокированные группы в набор не попадают: чекбокс такой группы
     * на экране недоступен, и отмеченный по умолчанию он отправил бы на
     * сервер то, что удаление отказалось бы выполнять.
     */
    public Set<DeletionGroupKind> defaultGroups(DeletionPreview preview) {

        Set<DeletionGroupKind> selected = EnumSet.noneOf(DeletionGroupKind.class);

        Set<DeletionGroupKind> blocked = preview.blockedGroups();

        preview.groups().stream()
                .filter(group -> !group.isEmpty())
                .filter(group -> group.kind().isDefaultSelected())
                .filter(group -> !blocked.contains(group.kind()))
                .forEach(group -> selected.add(group.kind()));

        return selected;
    }

    /** Разрешённые для типа записи группы — форма не должна предлагать лишнего. */
    public Set<DeletionGroupKind> allowedGroups(DeletionTargetKind kind) {
        return kind == DeletionTargetKind.PRODUCTION_ORDER
                ? EnumSet.of(
                        DeletionGroupKind.NESTED_ZNP,
                        DeletionGroupKind.ORDER_ITEMS,
                        DeletionGroupKind.RELEASE_SERIALS,
                        DeletionGroupKind.SCHEDULE_ROWS,
                        DeletionGroupKind.PLANNED_OPERATIONS,
                        DeletionGroupKind.PERFORMED_OPERATIONS,
                        DeletionGroupKind.KITTING_LINKS,
                        DeletionGroupKind.REPAIR_KITTING_ITEMS,
                        DeletionGroupKind.MOVEMENT_JOURNAL
                )
                : EnumSet.of(
                        DeletionGroupKind.BATCH_SERIALS,
                        DeletionGroupKind.MOVEMENT_JOURNAL,
                        DeletionGroupKind.INPUT_CONTROL_CARDS,
                        DeletionGroupKind.QUARANTINE_TRANSFERS,
                        DeletionGroupKind.BATCH_HEADER
                );
    }

    /* ================================================================
     * Поиск записей для удаления
     * ================================================================ */

    /** Действующие заказы по номеру — список выбора. */
    @Transactional(readOnly = true)
    public List<ProductionOrder> findOrders(String term) {

        String pattern = "%" + (term == null ? "" : term.trim()) + "%";

        return productionOrderRepository.search(
                pattern,
                org.springframework.data.domain.PageRequest.of(0, 30)
        );
    }

    /** Партии прихода по ордеру или поставщику — список выбора. */
    @Transactional(readOnly = true)
    public List<CellReceivingBatch> findBatches(String term) {

        String needle = term == null ? "" : term.trim().toLowerCase();

        return batchRepository.findAllDetailed().stream()
                .filter(batch -> needle.isEmpty()
                        || batch.getIncomingOrderNumber().toLowerCase()
                               .contains(needle)
                        || batch.getSupplierName().toLowerCase().contains(needle))
                .limit(30)
                .toList();
    }

    /* ================================================================
     * Служебное
     * ================================================================ */

    private DeletionPreview preview(DeletionTargetKind kind,
                                    Long id,
                                    String label,
                                    String subtitle,
                                    List<DeletionGroup> groups,
                                    List<DeletionWarning> warnings,
                                    DeletionAction action) {

        List<DeletionGroup> nonEmpty = groups.stream()
                .filter(group -> !group.isEmpty())
                .toList();

        List<DeletionWarning> sorted = new ArrayList<>(warnings);

        sorted.sort(java.util.Comparator
                .comparingInt((DeletionWarning warning) ->
                        warning.level().ordinal())
                .reversed());

        Set<DeletionGroupKind> defaultSelection =
                EnumSet.noneOf(DeletionGroupKind.class);

        nonEmpty.stream()
                .filter(group -> group.kind().isDefaultSelected())
                .forEach(group -> defaultSelection.add(group.kind()));

        return new DeletionPreview(
                kind,
                id,
                label,
                subtitle,
                nonEmpty,
                sorted,
                action,
                signature(kind, id, defaultSelection)
        );
    }

    /**
     * Заказ и все вложенные в него ЗНП, родитель первым.
     *
     * Обход в ширину с защитой от цикла: колонка родителя формально
     * допускает кольцо (CHECK не выражает рекурсию, см. V21), и
     * зациклиться на нём значит уйти в бесконечный обход до таймаута
     * транзакции.
     */
    private List<ProductionOrder> orderSubtree(Long orderId) {

        List<ProductionOrder> subtree = new ArrayList<>();
        List<Long> visited = new ArrayList<>();

        visited.add(orderId);
        subtree.add(requireOrder(orderId));

        for (int index = 0; index < subtree.size(); index++) {

            Long current = subtree.get(index).getId();

            for (ProductionOrder child
                    : productionOrderRepository.findActiveChildren(current)) {

                if (visited.contains(child.getId())) {
                    continue;
                }

                visited.add(child.getId());
                subtree.add(child);
            }
        }

        return subtree;
    }

    private ProductionOrder requireOrder(Long id) {
        return productionOrderRepository.findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ на производство не найден"
                ));
    }

    private CellReceivingBatch requireBatch(Long id) {
        return batchRepository.findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Партия оприходования не найдена"
                ));
    }

    private static String orderSubtitle(ProductionOrder order) {

        StringBuilder subtitle = new StringBuilder(order.getLevelDisplayName());

        subtitle.append(" · ").append(order.getStatusDisplayName());

        if (!"—".equals(order.getCustomerOrderNumber())) {
            subtitle.append(" · заказ покупателя ")
                    .append(order.getCustomerOrderNumber());
        }

        if (order.getParentProductionOrder() != null) {
            subtitle.append(" · внутри ").append(order.getParentOrderNumber());
        }

        return subtitle.toString();
    }

    private static String batchLabel(CellReceivingBatch batch) {
        return "ордер " + batch.getIncomingOrderNumber()
                + ", " + batch.getSupplierName();
    }

    private static String batchSubtitle(CellReceivingBatch batch) {
        return batch.getCellLabel()
                + " · " + batch.getNumberRangeLabel()
                + " · " + batch.getQuantity() + " шт."
                + " · " + batch.getStatusLabel();
    }

    private static String namesSuffix(List<String> names) {
        return names.isEmpty() ? "" : " (" + join(names) + ")";
    }

    private static String join(List<String> values) {
        return values.size() <= 6
                ? String.join(", ", values)
                : String.join(", ", values.subList(0, 6)) + " …";
    }

    private static String shorten(String value, int limit) {
        if (value == null) {
            return null;
        }

        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
