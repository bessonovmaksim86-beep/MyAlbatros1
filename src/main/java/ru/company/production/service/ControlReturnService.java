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
 *   * «Отправить на доработку» — проведение снимается со всех строк
 *     графика этого экземпляра, на каждую снятую строку заводится
 *     {@link ControlReturn}. Сроки (planned_date) не меняются: доработка
 *     выполняется в те же даты, иначе график перестал бы соответствовать
 *     обещанию покупателя. Переделка закрывается обычным повторным
 *     проведением строки (см. {@link #resolveReturns}).
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
     * Отправить выбранные экземпляры на доработку.
     *
     * Снимается проведение со всех проведённых строк каждого затронутого
     * экземпляра, а не только с выбранных: выборка приходит по операции
     * контроля, а переделывать цех обязан изделие целиком — иначе
     * следующая по маршруту работа осталась бы разблокированной над
     * недоделанным изделием.
     */
    @Transactional
    public String sendToRework(List<Long> rowIds, String reason, AppUser actor) {

        String defect = requireReason(reason);
        List<OrderScheduleRow> rows = requireRows(rowIds, actor);

        Map<String, OrderScheduleRow> instances = instanceSamples(rows);
        String controlOperation = operationLabel(rows.get(0));

        /*
         * Снятие предшествует записям возврата: строка плана обязана
         * оказаться незакрытой до того, как по ней появится открытая
         * переделка, иначе повторное проведение закрыло бы возврат,
         * которого ещё нет.
         */
        LocalDateTime now = LocalDateTime.now();
        List<OrderScheduleRow> unwritten = new ArrayList<>();

        for (OrderScheduleRow sample : instances.values()) {
            for (OrderScheduleRow row : completedRowsOfInstance(sample)) {
                row.setCompletedAt(null);
                row.setCompletedBy(null);
                scheduleRowRepository.save(row);
                unwritten.add(row);
            }
        }

        if (unwritten.isEmpty()) {
            throw new IllegalStateException(
                    "Нечего возвращать: выбранные экземпляры не проведены"
            );
        }

        List<ControlReturn> returns = new ArrayList<>();

        for (OrderScheduleRow row : unwritten) {
            ControlReturn ret = new ControlReturn();
            ret.setScheduleRow(row);
            ret.setReason(defect);
            ret.setControlOperation(controlOperation);
            ret.setReturnedBy(actor);
            ret.setReturnedAt(now);
            returns.add(ret);
        }

        controlReturnRepository.saveAll(returns);

        return "Возвращено на доработку: " + returns.size();
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

        return rows;
    }

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
