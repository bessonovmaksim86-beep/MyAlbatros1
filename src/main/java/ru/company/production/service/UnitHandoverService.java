package ru.company.production.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.UnitHandover;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.UnitHandoverRepository;

/**
 * Передача изделия со склада в производственное подразделение с подтверждением получателя.
 *
 * <p>Пока комплектовщик вносит изделие в чужой заказ, оно физически ещё на складе, а цех уже
 * считает его своим. До V81 позиция просто оставалась в реестре наличия с плашкой «В заказе»:
 * кладовщик видел её на полке, отбраковка звала её «Цех: Склад полуфабрикатов», и никто не
 * отвечал за то, доехала ли она. Теперь перемещение оформляется строкой {@link UnitHandover}:
 * склад передаёт, получатель подтверждает, и только с подтверждением позиция уходит из наличия.</p>
 *
 * <p>Инициатор — не кладовщик и не цех, а факт размещения: передачу создаёт тот же код, который
 * привязывает изделие к заказу ({@link SubassemblyKittingService}). Подразделение-получатель
 * берётся из маршрута этого заказа, а не из выбора пользователя, — иначе цех мог бы принять то,
 * что ему не предназначалось.</p>
 */
@Service
public class UnitHandoverService {

    /** Подразделение склада полуфабрикатов (миграция V2): его позиции передаются. */
    public static final String WAREHOUSE_UNIT_CODE = "SEMI_FINISHED_PRODUCT_WAREHOUSE";

    private final UnitHandoverRepository handoverRepository;
    private final OrganizationUnitRepository unitRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final SerialMovementJournalService journal;

    public UnitHandoverService(
            UnitHandoverRepository handoverRepository,
            OrganizationUnitRepository unitRepository,
            OrderScheduleRowRepository scheduleRowRepository,
            SerialMovementJournalService journal
    ) {
        this.handoverRepository = handoverRepository;
        this.unitRepository = unitRepository;
        this.scheduleRowRepository = scheduleRowRepository;
        this.journal = journal;
    }

    /**
     * Оформляет передачу изделий, внесённых в заказ другого подразделения.
     *
     * <p>Возвращает созданные строки — они же идут в журнал движения. Передача не создаётся,
     * когда заказ принадлежит самому складу (изделие никуда не едет) и когда маршрут заказа ещё
     * не построен: заказ не взят в работу, подтверждать приёмку некому.</p>
     *
     * @param serials   внесённые в заказ изделия
     * @param order     заказ-получатель
     * @param operation операция комплектования, в которую внесли изделия
     * @param actor     кладовщик, оформивший внесение
     * @return созданные передачи (пустой список, если передавать нечего)
     */
    @Transactional
    public List<UnitHandover> openForOrder(List<ProductSerial> serials, ProductionOrder order,
            Operation operation, AppUser actor) {
        if (serials == null || serials.isEmpty() || order == null) {
            return List.of();
        }
        OrganizationUnit toUnit = receivingUnitOf(order);
        if (toUnit == null) {
            return List.of();
        }
        OrganizationUnit fromUnit = warehouseUnit();
        if (fromUnit == null || fromUnit.getId().equals(toUnit.getId())) {
            return List.of();
        }

        LocalDateTime moment = LocalDateTime.now();
        List<UnitHandover> saved = new ArrayList<>();

        for (ProductSerial serial : serials) {
            if (serial == null || serial.getFullNumber() == null) {
                continue;
            }
            /*
             * Один номер в одном заказе передаётся один раз: повторное внесение (кладовщик
             * доложил ещё одну ячейку в тот же заказ) не должно плодить строки приёмки, по которым
             * цех дважды подписывал бы получение.
             */
            if (handoverRepository.existsByFullNumberAndToOrder_IdAndAcceptedFalseAndRejectedFalse(
                    serial.getFullNumber(), order.getId())) {
                continue;
            }

            UnitHandover handover = new UnitHandover();
            handover.setFullNumber(serial.getFullNumber());
            handover.setSerial(serial);
            handover.setClassifier(serial.getProductClassifier());
            // Вид фиксируется на момент передачи: тем временем позицию могут отметить готовой
            // ячейкой, а карточка приёмки обязана показывать то, что реально увезли со склада.
            handover.setCellKind(QuarantineCellKind.ofCellReady(serial.isCellReady()));
            handover.setFromUnit(fromUnit);
            handover.setToUnit(toUnit);
            handover.setToOrder(order);
            handover.setToOrderNumber(order.getNumber());
            handover.setToOperation(operation);
            handover.setTransferredBy(actor);
            handover.setTransferredAt(moment);
            saved.add(handoverRepository.save(handover));
        }

        journal.recordUnitTransferred(saved, actor, moment);
        return saved;
    }

    /**
     * Подтверждает приёмку: с этой минуты позиция уходит со склада и исчезает из реестра наличия.
     *
     * <p>Подразделение проверяется здесь, а не в контроллере: ссылка приёма одна и та же для
     * всех, и без границы мастер одного цеха закрыл бы приёмку, предназначенную другому.</p>
     *
     * @return {@code null} — приёмка закрыта; текст ошибки — если передача уже закрыта или чужая
     */
    @Transactional
    public String accept(Long id, Long unitId, AppUser actor) {
        Optional<UnitHandover> found = handoverRepository.findPendingForUpdate(id);
        if (found.isEmpty()) {
            return "Передача уже закрыта — обновите страницу.";
        }
        UnitHandover handover = found.get();
        if (isForeignUnit(handover, unitId)) {
            return "Эта позиция предназначена другому подразделению.";
        }
        LocalDateTime moment = LocalDateTime.now();
        handover.setAccepted(true);
        handover.setAcceptedBy(actor);
        handover.setAcceptedAt(moment);
        handoverRepository.save(handover);
        journal.recordUnitAccepted(handover, actor, moment);
        return null;
    }

    /**
     * Возвращает передачу складу: позиция снова лежит на полке и остаётся в реестре наличия.
     *
     * @return {@code null} — возврат оформлен; текст ошибки — если передача уже закрыта или чужая
     */
    @Transactional
    public String reject(Long id, Long unitId, String reason, AppUser actor) {
        Optional<UnitHandover> found = handoverRepository.findPendingForUpdate(id);
        if (found.isEmpty()) {
            return "Передача уже закрыта — обновите страницу.";
        }
        UnitHandover handover = found.get();
        if (isForeignUnit(handover, unitId)) {
            return "Эта позиция предназначена другому подразделению.";
        }
        LocalDateTime moment = LocalDateTime.now();
        handover.setRejected(true);
        handover.setRejectionReason(reason == null || reason.isBlank() ? null : reason.trim());
        handover.setRejectedBy(actor);
        handover.setRejectedAt(moment);
        handoverRepository.save(handover);
        journal.recordUnitRejected(handover, actor, moment, handover.getRejectionReason());
        return null;
    }

    private static boolean isForeignUnit(UnitHandover handover, Long unitId) {
        return handover.getToUnit() == null
                || unitId == null
                || !unitId.equals(handover.getToUnit().getId());
    }

    /** Ожидающие подтверждения приёмки передачи подразделения — по времени передачи. */
    @Transactional(readOnly = true)
    public List<UnitHandover> pendingForUnit(Long unitId) {
        return unitId == null ? List.of() : handoverRepository.findPendingForUnit(unitId);
    }

    /** Сколько позиций ждут подтверждения — счётчик пункта меню цеха. */
    @Transactional(readOnly = true)
    public long pendingCount(Long unitId) {
        return unitId == null ? 0
                : handoverRepository.countByToUnit_IdAndAcceptedFalseAndRejectedFalse(unitId);
    }

    /** Подтверждённые приёмкой номера: реестр склада снимает их с полок. */
    @Transactional(readOnly = true)
    public java.util.Set<String> acceptedNumbers() {
        return handoverRepository.findAcceptedFullNumbers();
    }

    /** Номера, переданные и не подтверждённые: реестр склада помечает их плашкой ожидания. */
    @Transactional(readOnly = true)
    public java.util.Set<String> pendingNumbers() {
        return handoverRepository.findPendingFullNumbers();
    }

    /**
     * Подразделение-получатель изделий заказа: первая по маршруту операция чужого подразделения.
     *
     * <p>Маршрут — строки плана (V56), которые создаются при взятии заказа в работу. Складская
     * приёмка в начале маршрута пропускается: её подразделение и есть отправитель, поэтому
     * получателем считается следующая операция, принадлежащая не складу.</p>
     *
     * @return {@code null}, если маршрут ещё не построен или весь принадлежит складу
     */
    @Transactional(readOnly = true)
    public OrganizationUnit receivingUnitOf(ProductionOrder order) {
        if (order == null || order.getId() == null) {
            return null;
        }
        String warehouseCode = WAREHOUSE_UNIT_CODE;
        return scheduleRowRepository
                .findRouteRowEntitiesOfProductionOrders(List.of(order.getId())).stream()
                .filter(row -> row.getOperation() != null)
                .filter(row -> row.getOrganizationUnit() != null)
                .filter(row -> !warehouseCode.equalsIgnoreCase(
                        row.getOrganizationUnit().getCode()))
                .min(java.util.Comparator.comparing(
                        UnitHandoverService::routeOrder,
                        java.util.Comparator.nullsLast(
                                java.util.Comparator.naturalOrder())))
                .map(OrderScheduleRow::getOrganizationUnit)
                .orElse(null);
    }

    /**
     * Где позиция числится сейчас: подразделение с родовой подписью («Цех: …», «Склад: …»).
     *
     * <p>Матрица комплектования указывает операцию, под которую заказ заведён, и для заказов со
     * складской приёмкой этой операцией оказывается приёмка склада. Отбраковка подписывала её как
     * «Цех: Склад полуфабрикатов», хотя ячейку давно увезли в сборку. Настоящий держатель —
     * подразделение приёмки: подтверждённая передача, а пока её нет, первая по маршруту заказа
     * операция чужого подразделения.</p>
     *
     * @return подпись местоположения; {@code null}, если подразделение не определить вовсе
     */
    @Transactional(readOnly = true)
    public String locationLabel(ProductionOrder order, String fullNumber) {
        return unitLocationLabel(currentUnitOf(order, fullNumber));
    }

    /**
     * Подпись местоположения по подразделению: род («Цех:» / «Склад:») берётся из его кода.
     *
     * Род подписи обязателен: заявка на отбраковку читается бюро как «где искать ячейку», и
     * название подразделения без него не отвечает на этот вопрос.
     *
     * @return {@code null}, если подразделение не задано — вызывающий код подписывает строку сам
     */
    public static String unitLocationLabel(OrganizationUnit unit) {
        if (unit == null || unit.getName() == null || unit.getName().isBlank()) {
            return null;
        }

        return (WAREHOUSE_UNIT_CODE.equalsIgnoreCase(unit.getCode())
                ? "Склад: "
                : "Цех: ") + unit.getName().trim();
    }

    /**
     * Подразделение, у которого позиция находится сейчас.
     *
     * <p>Закрытая передача точнее маршрута: маршрут говорит, куда позицию планировали, а
     * приёмка — куда её действительно отдали. Если приёмки ещё нет (заказ не взят в работу или
     * позиция вложена до V81), берётся маршрут.</p>
     */
    @Transactional(readOnly = true)
    public OrganizationUnit currentUnitOf(ProductionOrder order, String fullNumber) {
        if (order == null) {
            return null;
        }
        if (fullNumber != null) {
            Optional<UnitHandover> last = handoverRepository
                    .findFirstByFullNumberAndToOrder_IdOrderByIdDesc(fullNumber, order.getId());
            if (last.isPresent() && last.get().getToUnit() != null) {
                return last.get().getToUnit();
            }
        }
        return receivingUnitOf(order);
    }

    /**
     * Позиция строки маршрута: порядок плана (seq), при его отсутствии — позиция экземпляра.
     *
     * <p>Оба поля обнуляемы у старых строк, поэтому пустое значение уводится в конец: иначе
     * {@code min} выбрал бы строку с {@code null} вместо реальной первой операции цеха.</p>
     */
    private static Integer routeOrder(OrderScheduleRow row) {
        return row.getSeq() != null ? row.getSeq() : row.getInstanceIndex();
    }

    private OrganizationUnit warehouseUnit() {
        return unitRepository.findByCodeIgnoreCase(WAREHOUSE_UNIT_CODE).orElse(null);
    }
}
