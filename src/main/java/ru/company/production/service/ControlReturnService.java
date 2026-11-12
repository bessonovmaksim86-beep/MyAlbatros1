package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.PendingDefectForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.DefectConfirmed;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.PendingDefect;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.Role;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Возвраты с контроля БТК — доработка и изолятор брака.
 *
 * Действия контроля живут в «Моих операциях» и вызываются по чекбоксам
 * проведённых экземпляров:
 *
 *   * «Отправить на доработку» — сигнал цеху о возврате: на каждую
 *     проведённую строку экземпляра заводится {@link ControlReturn},
 *     но проведение НЕ снимается. Начальник цеха в разделе «Возврат на
 *     доработку» сам решает, какие операции переделывать, и снимает
 *     факт только с них (см. {@link ReworkReturnService#markForRework}).
 *     Сроки (planned_date) не меняются: доработка выполняется в те же
 *     даты, иначе график перестал бы соответствовать обещанию покупателя.
 *     Переделка закрывается обычным повторным проведением строки
 *     (см. {@link #resolveReturns}).
 *
 *   * «В изолятор брака» — путь заявки на отбраковку: номер изделия
 *     берётся из состава экземпляра, заявка создаётся и тут же
 *     подтверждается самим бюро, поэтому в очередь согласования она не
 *     попадает. Проведение с экземпляра тоже снимается: изделие ушло,
 *     и закрытая работа по нему осталась бы висеть в плане.
 *
 * Причина обязательна в обоих случаях: цех получает обратно уже
 * проведённую работу, и без указания, что именно не так, исполнитель не
 * знает, что переделывать.
 *
 * Проверки предшествуют записям: отказ обязан не оставлять полуснятого
 * плана, поэтому сначала подтверждается, что возвращать есть что и
 * никто не мешает браковать номер, и только потом меняются строки.
 */
@Service
@RequiredArgsConstructor
public class ControlReturnService {

    private final ControlReturnRepository controlReturnRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final ProductSerialRepository serialRepository;
    private final WorkshopDistributionSerialService serialService;
    private final WorkshopStaffService staffService;
    private final PendingDefectService pendingDefectService;

    /*
     * Возврат на доработку пишется в журнал перемещений: паспорт изделия
     * собирается по журналу, и без строки переделки в нём повторный
     * возврат выглядел бы первым, а закрытая переделка — сбоем
     * проведения.
     */
    private final SerialMovementJournalService journalService;

    /*
     * Сервис блокировки доработки: БТК не может перемещать позиции,
     * которые цех уже принял на переделку — это запрещено на
     * сервере, а не только скрыто в интерфейсе.
     */
    private final InstanceReworkService instanceReworkService;

    /*
     * Роли, ведущие контроль и возвращающие работу цеху: мастер и
     * контролёр БТК.
     *
     * Набор совпадает с признаком btkModule в «Мои операции»: пункт
     * интерфейса и право на адрес задаются одним и тем же условием,
     * иначе меню звало бы в 403.
     */
    private static final Set<Role> CONTROL_ROLES = Set.of(
            Role.BTK_MASTER,
            Role.BTK_EXECUTOR
    );

    /** Возвратом занимается только БТК. */
    public boolean isControlStaff(AppUser user) {
        return user != null
                && user.getRole() != null
                && CONTROL_ROLES.contains(user.getRole());
    }

    /** Открытые возвраты указанных строк — подсветка переделки в списке. */
    @Transactional(readOnly = true)
    public List<ControlReturn> findOpenReturns(Collection<Long> rowIds) {
        if (rowIds == null || rowIds.isEmpty()) {
            return List.of();
        }

        return controlReturnRepository.findOpenByRowIds(rowIds);
    }

    /**
     * Строки, по которым возврат заводился когда-либо.
     *
     * Открытых возвратов для этого мало: переделку закрывают повторным
     * проведением, и строка становится закрытой ровно в тот момент,
     * когда позиция снова попадает на контроль. Различить первую
     * переделку и повторную можно только по факту возврата, оставшемуся
     * в истории — строки возвратов не удаляются.
     */
    @Transactional(readOnly = true)
    public Set<Long> rowsWithReturnHistory(Collection<Long> rowIds) {

        if (rowIds == null || rowIds.isEmpty()) {
            return Set.of();
        }

        return new LinkedHashSet<>(
                controlReturnRepository.findRowIdsWithReturnHistory(rowIds));
    }

    /**
     * Строки, чей экземпляр ожидает решения цеха — открытый сигнал БТК
     * есть хотя бы по одной его строке.
     *
     * Нужен блокировке проведения: БТК возвращает изделие целиком, а не
     * одну операцию, поэтому сигнал, заведённый на строку монтажа,
     * касается и строки контроля того же изделия.
     */
    @Transactional(readOnly = true)
    public Set<Long> rowIdsOfSignaledInstances(Collection<Long> rowIds) {

        if (rowIds == null || rowIds.isEmpty()) {
            return Set.of();
        }

        return new LinkedHashSet<>(
                controlReturnRepository.findRowIdsOfSignaledInstances(rowIds));
    }

    /**
     * Отправить выбранные экземпляры на доработку.
     *
     * Возврат — сигнал цеху, а не отмена работы: проведение со строк
     * НЕ снимается. Начальник цеха в разделе «Возврат на доработку»
     * сам решает, какие операции переделывать, и снимает проведение
     * только с них (см. {@link ReworkReturnService#markForRework}).
     *
     * Возврат заводится на каждую проведённую строку экземпляра, а не
     * только на выбранную: подсветка переделки обязана появиться у
     * каждого исполнителя по его собственной работе, иначе цех увидел
     * бы возврат только на операции контроля.
     *
     * Возврат пишется и в журнал перемещений — по одному событию на
     * экземпляр: переделка одного изделия обязана читаться в его
     * паспорте столько раз, сколько её возвращали, иначе повторный
     * возврат выглядел бы первым, а закрытая переделка — сбоем
     * проведения.
     */
    @Transactional
    public String sendToRework(List<Long> rowIds, String reason, AppUser actor) {

        String defect = requireReason(reason);
        List<OrderScheduleRow> rows = requireRows(rowIds, actor);

        Map<String, OrderScheduleRow> instances = instanceSamples(rows);
        String controlOperation = operationLabel(rows.get(0));

        LocalDateTime now = LocalDateTime.now();
        List<ControlReturn> returns = new ArrayList<>();

        for (OrderScheduleRow sample : instances.values()) {

            /*
             * Выбранная строка попадает в возврат сама, даже оставаясь
             * непроведённой: контролёр вправе завернуть ячейку, не
             * закрывая контроль. Без этого возврат заводился только на
             * закрытые работы цеха, а отмеченная позиция в списке БТК
             * не менялась — действие выглядело не сработавшим.
             */
            Set<Long> returned = new LinkedHashSet<>();

            for (OrderScheduleRow chosen : rows) {
                if (!instanceKey(chosen).equals(instanceKey(sample))) {
                    continue;
                }

                returns.add(newReturn(
                        chosen, defect, controlOperation, actor, now));
                returned.add(chosen.getId());
            }

            for (OrderScheduleRow row : completedRowsOfInstance(sample)) {

                if (!returned.add(row.getId())) {
                    continue;
                }

                returns.add(newReturn(
                        row, defect, controlOperation, actor, now));
            }
        }

        /*
         * Отказ на пустом выборе уже отвергнут в requireRows, а
         * выбранная строка попадает в возврат всегда: ветка «нечего
         * возвращать» осталась бы недостижимым кодом.
         */
        controlReturnRepository.saveAll(returns);

        /*
         * Событие журнала — после возврата: паспорт обязан показать
         * доработку ровно тогда, когда сигнал цеху отправлен, а не
         * раньше. Экземпляр без номера изделия (выпуск ещё не создан,
         * склад не внёс ячейку) события не получает: журналу не о чём
         * писать, а выдуманный номер исказил бы паспорт.
         */
        for (OrderScheduleRow sample : instances.values()) {
            ProductSerial serial = serialOfInstance(sample);

            if (serial == null || serial.getFullNumber() == null) {
                continue;
            }

            journalService.recordReturnedToRework(
                    serial.getFullNumber(),
                    serial.getId(),
                    defect,
                    actor,
                    now
            );
        }

        return "Возвращено на доработку: " + instances.size();
    }

    private static ControlReturn newReturn(OrderScheduleRow row,
                                           String defect,
                                           String controlOperation,
                                           AppUser actor,
                                           LocalDateTime now) {
        ControlReturn ret = new ControlReturn();
        ret.setScheduleRow(row);
        ret.setReason(defect);
        ret.setControlOperation(controlOperation);
        ret.setReturnedBy(actor);
        ret.setReturnedAt(now);
        return ret;
    }

    /**
     * Отправить выбранные экземпляры в изолятор брака.
     *
     * Идёт тем же путём, что заявка цеха, но решается на месте: БТК и
     * есть бюро, принимающее решение, и отдельная очередь согласования
     * для его собственного вывода не нужна.
     */
    @Transactional
    public String sendToQuarantine(List<Long> rowIds,
                                   String reason,
                                   AppUser actor) {

        String defect = requireReason(reason);
        List<OrderScheduleRow> rows = requireRows(rowIds, actor);

        Map<String, OrderScheduleRow> instances = instanceSamples(rows);

        /*
         * Номера собираются до любых записей: заявка на номер, по которому
         * бюро уже решает другую заявку, отклоняется, и отказ обязан
         * случиться до снятого проведения, иначе план разошёлся бы с
         * изолятором.
         */
        Map<String, Long> numbers = new LinkedHashMap<>();

        for (Map.Entry<String, OrderScheduleRow> entry : instances.entrySet()) {
            ProductSerial serial = serialOfInstance(entry.getValue());

            if (serial == null) {
                throw new IllegalStateException(
                        "Экземпляр «" + entry.getValue().getOperation().getOperationName().getName()
                                + "» не имеет номера изделия — в изолятор брака "
                                + "отправляют ячейку или подсборку, внесённую складом"
                );
            }

            String number = serial.getFullNumber();

            if (number == null) {
                throw new IllegalStateException(
                        "Изделий экземпляр не имеет полного номера в реестре"
                );
            }

            if (pendingDefectService.hasPendingByNumber(number)) {
                throw new IllegalStateException(
                        "На номер " + number
                                + " уже есть заявка, ожидающая решения бюро"
                );
            }

            numbers.put(number, entry.getValue().getId());
        }

        String stage = operationLabel(rows.get(0));
        int isolated = 0;

        for (String number : numbers.keySet()) {
            PendingDefectForm form = new PendingDefectForm();
            form.setFullNumber(number);
            form.setDefectReason(defect);
            form.setDefectStage(stage);

            PendingDefect defect0 = pendingDefectService.create(form, actor);

            pendingDefectService.applyDefectDecision(
                    defect0.getId(),
                    DefectConfirmed.CONFIRMED,
                    defect,
                    actor
            );

            isolated++;
        }

        /*
         * Проведение снимается после перемещения: releaseZnpQuota внутри
         * решения бюро идёт от серийника и матрицы, а строки плана этого
         * экземпляра снимает здесь — для группового ЗНП он сам ничего не
         * находит (у заказа нет выпуска).
         */
        int unwritten = 0;

        for (OrderScheduleRow sample : instances.values()) {
            for (OrderScheduleRow row : completedRowsOfInstance(sample)) {
                row.setCompletedAt(null);
                row.setCompletedBy(null);
                scheduleRowRepository.save(row);
                unwritten++;
            }
        }

        return "В изолятор брака: " + isolated
                + (unwritten > 0 ? ", снято проведений: " + unwritten : "");
    }

    /**
     * Закрыть открытые возвраты строки — её провели повторно.
     *
     * Вызывается из {@link WorkshopDistributionService#completeRow}:
     * повторное проведение есть единственный способ закрыть переделку,
     * и закрывать её надо ровно тем же фактом, который закрывает строку
     * плана. Иначе подсветка осталась бы висеть над сделанной работой.
     */
    @Transactional
    public void resolveReturns(Long rowId, AppUser performer) {

        if (rowId == null || performer == null) {
            return;
        }

        List<ControlReturn> open =
                controlReturnRepository.findOpenByRowIds(List.of(rowId));

        if (open.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (ControlReturn ret : open) {
            ret.setResolvedAt(now);
            ret.setResolvedBy(performer);
        }

        controlReturnRepository.saveAll(open);
    }

    /* ============================================================
       ПРОВЕРКИ
       ============================================================ */

    /** Причина обязательна: без неё исполнитель не знает, что переделывать. */
    private static String requireReason(String reason) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Укажите причину возврата — цех обязан видеть, что переделывать"
            );
        }

        String defect = reason.trim();

        if (defect.length() > REASON_LIMIT) {
            throw new IllegalArgumentException(
                    "Причина длиннее " + REASON_LIMIT + " символов"
            );
        }

        return defect;
    }

    private static final int REASON_LIMIT = 500;

    /**
     * Строки возврата: они существуют, не проведены чужим подразделением
     * и принадлежат тому же праву, что и проведение.
     *
     * Граница — {@link WorkshopStaffService#unitScope}: руководителю
     * службы качества принадлежат оба бюро, и сравнение с единственным
     * подразделением его карточки отклонило бы любую его работу.
     *
     * Проведённую позицию перемещать нельзя никуда — ни в изолятор
     * брака, ни назад на доработку: вердикт контроля уже вынесен, и
     * второе перемещение задним числом переписывал бы принятую работу.
     * Исключение — позиция, уже бывшая в доработке: её возвращают не
     * раз, пока переделку не закрыли повторным проведением (проверка
     * читает историю возвратов — {@link #assertMovable}).
     */
    private List<OrderScheduleRow> requireRows(List<Long> rowIds, AppUser actor) {

        if (!isControlStaff(actor)) {
            throw new IllegalStateException(
                    "Возвращать с контроля вправе только бюро технического контроля"
            );
        }

        if (rowIds == null || rowIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Отметьте экземпляры, которые возвращаете с контроля"
            );
        }

        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(rowIds));
        List<OrderScheduleRow> rows = scheduleRowRepository.findAllById(ids);

        if (rows.size() != ids.size()) {
            throw new IllegalArgumentException(
                    "Часть выбранных экземпляров уже не найдена — обновите список"
            );
        }

        List<Long> scope = staffService.unitScope(actor);

        for (OrderScheduleRow row : rows) {
            if (row.getOrganizationUnit() == null
                    || row.getOrganizationUnit().getId() == null
                    || !scope.contains(row.getOrganizationUnit().getId())) {
                throw new IllegalStateException(
                        "Возвращать можно работы своего подразделения"
                );
            }
        }

        assertMovable(rows);

        return rows;
    }

    /**
     * Проведённые строки, не бывшие в доработке, перемещению не подлежат.
     *
     * Отметить можно непроведённый экземпляр — контролёр выносит вердикт
     * до проведения — и проведённый, который уже возвращали на доработку
     * хотя бы раз: переделку закрывают повторным проведением, и по
     * закрытому возврату нельзя отличить повторную переделку от первой.
     * Поэтому проверяем не открытые, а все возвраты — строки не удаляются,
     * а закрываются {@code resolved_at} при повторном проведении.
     *
     * Дополнительно БТК запрещено перемещать позиции, которые цех
     * уже принял на доработку — у них есть открытый {@link ReworkReturn}.
     * Такие позиции находятся в процессе переделки, и повторное
     * перемещение переписало бы маршрут изделия, по которому решение
     * ещё не принято.
     *
     * Читаем одним запросом по всем rowIds: проверка обязана отвергнуть
     * отказ до любых записей, а построчный запрос превратил бы одну
     * отметку в десяток обращений к базе.
     */
    private void assertMovable(List<OrderScheduleRow> rows) {

        List<Long> completedIds = new ArrayList<>();

        for (OrderScheduleRow row : rows) {
            if (row.getCompletedAt() != null) {
                completedIds.add(row.getId());
            }
        }

        if (completedIds.isEmpty()) {
            return;
        }

        Set<Long> hadReturn = new LinkedHashSet<>(
                controlReturnRepository.findRowIdsWithReturnHistory(completedIds));

        for (Long id : completedIds) {
            if (hadReturn.contains(id)) {
                continue;
            }

            OrderScheduleRow row = rows.stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst().orElse(null);

            throw new IllegalStateException(
                    "Позиция проведена "
                            + (row != null && row.getCompletedAt() != null
                                ? " " + RU_DATE_TIME.format(row.getCompletedAt())
                                : "")
                            + " — перемещать её нельзя. На доработку возвращают "
                            + "только позиции, уже бывшие в доработке"
            );
        }

        /*
         * Строки, чья переделка не завершена: БТК не может перемещать
         * позиции, которые цех уже принял на доработку.
         */
        Set<Long> incompleteRows = instanceReworkService
                .incompleteRowIds(completedIds);

        if (!incompleteRows.isEmpty()) {
            throw new IllegalStateException(
                    "Позиция на доработке — перемещение запрещено. "
                            + "Доработка закроется после повторного проведения"
            );
        }
    }

    /** Подпись снятия проведения в отказе — тот же формат, что в списках. */
    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /* ============================================================
       ЭКЗЕМПЛЯРЫ ИХ СТРОКИ И НОМЕРЫ
       ============================================================ */

    /**
     * По одному образцу затронутого экземпляра.
     *
     * Ключ — пара «основание плана + позиция + номер внутри позиции»,
     * тот же, что связывает строку графика с изделием (см.
     * {@code WorkshopDistributionSerialService#resolve}). Выбранные строки
     * соседних изделий одной операции обязаны дать разные экземпляры, а
     * строки одного изделия — один, иначе снятие прошло бы по кругу
     * дважды.
     */
    private static Map<String, OrderScheduleRow> instanceSamples(
            List<OrderScheduleRow> rows
    ) {
        Map<String, OrderScheduleRow> samples = new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            samples.putIfAbsent(instanceKey(row), row);
        }

        return samples;
    }

    private static String instanceKey(OrderScheduleRow row) {

        if (row.getCustomerOrder() != null
                && row.getCustomerOrder().getId() != null) {
            return "co:" + row.getCustomerOrder().getId()
                    + ":" + positionOf(row) + ":" + indexOf(row);
        }

        if (row.getProductionOrder() != null
                && row.getProductionOrder().getId() != null) {
            return "po:" + row.getProductionOrder().getId()
                    + ":" + positionOf(row) + ":" + indexOf(row);
        }

        return "row:" + row.getId();
    }

    private static int positionOf(OrderScheduleRow row) {
        return row.getItemPosition() == null ? 0 : row.getItemPosition();
    }

    private static int indexOf(OrderScheduleRow row) {
        return row.getInstanceIndex() == null ? 0 : row.getInstanceIndex();
    }

    /**
     * Проведённые строки экземпляра — то, с чего снимается факт.
     *
     * У заказа покупателя план привязан к нему, у группового ЗНП — к
     * самому заказу на производство (V56), и ветвь основания определяет
     * запрос: ошибочная ветвь вернула бы пустой список и молча оставила
     * работу проведённой.
     */
    private List<OrderScheduleRow> completedRowsOfInstance(OrderScheduleRow sample) {

        if (sample.getCustomerOrder() != null
                && sample.getCustomerOrder().getId() != null) {
            return scheduleRowRepository.findCompletedRowsOfInstance(
                    sample.getCustomerOrder().getId(),
                    sample.getItemPosition(),
                    sample.getInstanceIndex()
            );
        }

        if (sample.getProductionOrder() != null
                && sample.getProductionOrder().getId() != null) {
            return scheduleRowRepository
                    .findCompletedRowsOfProductionOrderInstance(
                            sample.getProductionOrder().getId(),
                            sample.getItemPosition(),
                            sample.getInstanceIndex()
                    );
        }

        return List.of();
    }

    /**
     * Изделие экземпляра: серийный номер выпуска либо внесённая ячейка.
     *
     * Те же два источника подписи, что у панели «Мои операции»: у строки
     * выпуска номера ячейки нет, у группового ЗНП — состава выпуска, и
     * перебирать их приходится оба, иначе изолятор не знал бы номер.
     */
    private ProductSerial serialOfInstance(OrderScheduleRow sample) {

        /*
         * Выпускающийся ЗНП — серийник из {@code resolve}:
         * product_serials → production_order_items.
         */
        Map<Long, ProductionOrderSerialProjection> release =
                serialService.resolve(List.of(sample));

        ProductionOrderSerialProjection serial = release.get(sample.getId());

        if (serial == null || serial.getSerialId() == null) {
            /*
             * Групповой ЗНП — ячейка из {@code resolveAttachedCells}:
             * serial_production_orders → product_serials.
             */
            Map<Long, AttachedCellView> cells =
                    serialService.resolveAttachedCells(List.of(sample));

            AttachedCellView cell = cells.get(sample.getId());

            if (cell == null || cell.getSerialId() == null) {
                return null;
            }

            return serialRepository
                    .findById(cell.getSerialId())
                    .orElse(null);
        }

        return serialRepository
                .findById(serial.getSerialId())
                .orElse(null);
    }

    /** Наименование операции контроля — этап, с которого вернули работу. */
    private static String operationLabel(OrderScheduleRow row) {

        if (row.getOperation() == null
                || row.getOperation().getOperationName() == null) {
            return "Контроль БТК";
        }

        return row.getOperation().getOperationName().getName();
    }
}
