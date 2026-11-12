package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ReworkReturnCard;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ReworkReturn;
import ru.company.production.entity.ReworkReturn.ReturnType;
import ru.company.production.entity.ReworkReturn.ReworkStatus;
import ru.company.production.entity.Role;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ReworkReturnRepository;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.ProductionOrderSerialProjection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Возврат на доработку — раздел начальника цеха.
 *
 * Цех видит изделия, возвращённые с контроля БТК, и решает, что с ними
 * делать: переделать свои операции сам или отправить изделие назад по
 * маршруту — в предшествующий цех.
 *
 * Ключевое отличие от {@link ControlReturnService}: БТК возвращает
 * изделие сигналом, не трогая проведения, а решение о том, какие именно
 * работы переделывать, принимает начальник цеха. Проведение снимается
 * только с отмеченных им строк — остальной маршрут остаётся закрытым.
 *
 * Карточка строится по экземпляру, а не по строке возврата: строка
 * контроля принадлежит бюро, а не цеху, поэтому цех собирает изделие по
 * своему подразделению — все операции маршрута, которые выполняет сам.
 */
@Service
@RequiredArgsConstructor
public class ReworkReturnService {

    private final ReworkReturnRepository reworkReturnRepository;
    private final ControlReturnRepository controlReturnRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final WorkshopStaffService staffService;

    /*
     * Подпись изделия карточки: серийник выпуска либо внесённая
     * ячейка. Тот же сервис, что подписывает экземпляры в «Моих
     * операциях» и в очереди распределения, — цех и исполнитель обязаны
     * видеть один и тот же номер одного и того же изделия.
     */
    private final WorkshopDistributionSerialService serialService;

    /*
     * Календарь переделки: снятая для переделки операция получает
     * ближайший рабочий день, а идущие после неё — следующие рабочие
     * дни, чтобы маршрут оставался непротиворечивым.
     */
    private final WorkingDaysCalculator workingDays;

    /* ============================================================
       СПИСОК РАЗДЕЛА
       ============================================================ */

    /**
     * Изделия, возвращённые на доработку, — карточка на экземпляр.
     *
     * Основание списка — открытые возвраты БТК: цеху показывают то, что
     * ему вернули. В карточку попадают все строки маршрута этого
     * экземпляра, принадлежащие подразделениям руководителя, — иначе он
     * не увидел бы, какие свои операции придётся переделывать.
     */
    @Transactional(readOnly = true)
    public List<ReworkReturnCard> findOpenReturns(AppUser supervisor) {

        if (supervisor == null) {
            return List.of();
        }

        List<Long> scope = staffService.unitScope(supervisor);

        if (scope.isEmpty()) {
            return List.of();
        }

        List<ControlReturn> open = controlReturnRepository.findAllOpen();

        if (open.isEmpty()) {
            return List.of();
        }

        /*
         * Возвраты одного экземпляра дают одну карточку: БТК возвращает
         * изделие целиком и на каждую снятую строку заведен свой
         * ControlReturn, иначе цех увидел бы одно изделие несколько раз.
         */
        Map<String, List<ControlReturn>> byInstance = new LinkedHashMap<>();

        for (ControlReturn ret : open) {
            OrderScheduleRow row = ret.getScheduleRow();

            if (row == null) {
                continue;
            }

            byInstance
                    .computeIfAbsent(instanceKey(row), key -> new ArrayList<>())
                    .add(ret);
        }

        List<ReworkReturnCard> cards = new ArrayList<>();

        for (Map.Entry<String, List<ControlReturn>> entry : byInstance.entrySet()) {

            ReworkReturnCard card = buildCard(
                    entry.getKey(),
                    entry.getValue().get(0).getScheduleRow(),
                    entry.getValue(),
                    scope
            );

            /*
             * Пустая карточка — экземпляр, к которому у цеха нет ни
             * одной работы: возврат чужого изделия показывать незачем.
             */
            if (card != null) {
                cards.add(card);
            }
        }

        return cards;
    }

    /**
     * Открытые возвраты цеха по указанным строкам — подсветка
     * переделки в «Мои операции».
     *
     * Сигнал БТК закрывается решением цеха, поэтому причину переделки
     * исполнитель читает отсюда: иначе снятое проведение осталось бы
     * без объяснения.
     */
    @Transactional(readOnly = true)
    public List<ReworkReturn> findOpenReturns(Collection<Long> rowIds) {

        if (rowIds == null || rowIds.isEmpty()) {
            return List.of();
        }

        return reworkReturnRepository.findOpenByRowIds(rowIds);
    }

    /**
     * Сколько изделий ждёт решения о доработке — цифра пункта меню.
     *
     * Отдельный запрос, а не {@link #findOpenReturns}: счётчик рисуется
     * на каждом экране модуля, и собирать ради одной цифры карточки со
     * всеми строками маршрута было бы потерей.
     */
    @Transactional(readOnly = true)
    public long countOpenReturns(AppUser supervisor) {

        if (supervisor == null) {
            return 0L;
        }

        List<Long> scope = staffService.unitScope(supervisor);

        return scope.isEmpty()
                ? 0L
                : controlReturnRepository.countOpenForUnits(scope);
    }

    /* ============================================================
       ДЕЙСТВИЯ НАЧАЛЬНИКА ЦЕХА
       ============================================================ */

    /**
     * Переделать отмеченные операции своими силами.
     *
     * Проведение снимается ровно с отмеченных строк — в отличие от
     * возврата БТК, где изделие снимают целиком. Начальник цеха видит
     * маршрут и отвечает за границу переделки: там, где работа принята
     * без замечаний, её не перепроводят.
     *
     * Отмеченная строка тянет за собой все проведённые строки того же
     * экземпляра, идущие в маршруте после неё: переделанная работа
     * не может оставаться под закрытой, иначе следующая по маршруту
     * осталась бы разблокированной над недоделанным изделием.
     *
     * Снятая строка возвращается в цех заново: получает ближайший
     * рабочий день вместо даты уже принятой работы, прежняя дата
     * сохраняется в возврате, а закрепление за исполнителем и рабочим
     * местом снимается — работа встаёт в очередь распределения, где
     * мастер назначит её снова. Иначе переделка осталась бы закрытой
     * датой под уже снятым фактом и не появилась бы ни в одном
     * рабочем списке цеха.
     *
     * На каждую снятую строку заводится {@link ReworkReturn}: исполнитель
     * видит причину по своей работе, а возврат остаётся в истории брака.
     * Сигнал БТК ({@link ControlReturn}) закрывается тем же фактом:
     * цех изделие принял и решение по нему вынес, — карточка уходит из
     * раздела, а причиной переделки для исполнителя служит собственный
     * возврат цеха.
     *
     * Каскад включён всегда и выбора на экране не имеет: отмечая
     * операцию, цех обязан переделать и всё, что по техпроцессу идёт
     * после неё, иначе закрытая приёмка осталась бы над недоделанным
     * изделием (пример: отмечено лакирование подсборка — возвратится в
     * работу и передача в БТК).
     */
    @Transactional
    public String markForRework(List<Long> rowIds,
                                String reason,
                                AppUser supervisor) {

        requireSupervisor(supervisor);

        List<OrderScheduleRow> chosen = requireRows(rowIds, supervisor);

        List<Long> scope = staffService.unitScope(supervisor);

        /*
         * Каскад считается до снятий: дата факта — признак того, что
         * строку ещё можно снять, и после снятия строка перестанет
         * попадать в выборку последующих.
         */
        Map<Long, OrderScheduleRow> unwritten = new LinkedHashMap<>();

        for (OrderScheduleRow row : chosen) {
            unwritten.putIfAbsent(row.getId(), row);

            for (OrderScheduleRow next : followingCompletedRows(row, scope)) {
                unwritten.putIfAbsent(next.getId(), next);
            }
        }

        /*
         * Поле причины на экране убрано: цех видит её в шапке карточки
         * («... Причина: ...»), и вводить то же самое второй раз
         * незачем. Причина берётся из открытого возврата БТК, ради
         * которого карточка и показана, — той же строкой, что в шапке.
         *
         * Читается причина построчно: одним запросом отмечены строки
         * разных изделий, а у каждого изделия своё основание возврата,
         * и одна причина на всю отправку подписала бы переделку чужим
         * браком.
         */
        Map<Long, String> controlReasons = controlReasonsOf(unwritten.keySet());

        String fallback = blankToNull(reason) != null
                ? reason
                : firstReason(controlReasons);

        LocalDateTime now = LocalDateTime.now();
        List<ReworkReturn> returns = new ArrayList<>();
        int alreadyOpen = 0;

        /*
         * Сколько строк этого экземпляра уже получили дату переделки.
         * Счётчик ведётся по изделию, а не сквозной нумерацией всей
         * отправки: даты обязаны расходиться внутри маршрута одного
         * изделия, а разные изделия одного запроса не обязаны идти
         * последовательно — иначе второе изделие ушло бы на дату
         * дальше, чем требует его собственный техпроцесс.
         */
        Map<String, Integer> reworkedPerInstance = new LinkedHashMap<>();

        for (OrderScheduleRow row : reworkOrder(unwritten)) {

            /*
             * Возврат заводится только на реально снятое проведение.
             * Строка, открытая ранее (возврат БТК старым порядком
             * снимал проведение сам), уже в переделке: повторное
             * снятие — пустое действие, а второй ReworkReturn удвоил
             * бы её в истории брака.
             */
            if (row.getCompletedAt() == null) {
                alreadyOpen++;
                continue;
            }

            String defect = requireReason(
                    controlReasons.getOrDefault(row.getId(), fallback)
            );

            LocalDate previousDate = row.getPlannedDate();

            row.setCompletedAt(null);
            row.setCompletedBy(null);

            /*
             * Закрепление снимается: работа, с которой сняли факт,
             * больше не «закреплена за своим исполнителем» — она
             * возвращается мастеру на новое распределение.
             */
            row.setWorkPlace(null);
            row.setExecutor(null);
            row.setAssignedBy(null);
            row.setAssignedAt(null);

            int indexInInstance = reworkedPerInstance.merge(
                    instanceKey(row), 1, Integer::sum
            ) - 1;

            row.setPlannedDate(reworkDate(indexInInstance));

            scheduleRowRepository.save(row);

            ReworkReturn ret = new ReworkReturn();
            ret.setScheduleRow(row);
            ret.setReason(defect);
            ret.setReturnType(ReturnType.INTERNAL);
            ret.setStatus(ReworkStatus.IN_REWORK);
            ret.setCascadeToFollowing(true);
            ret.setPreviousPlannedDate(previousDate);
            ret.setReturnedBy(supervisor);
            ret.setReturnedAt(now);

            returns.add(ret);
        }

        if (returns.isEmpty()) {
            return "Уже на доработке: " + alreadyOpen
                    + " — проведение снимать не требуется";
        }

        reworkReturnRepository.saveAll(returns);

        closeControlReturns(unwritten.values(), supervisor, now);

        return "Возвращено на доработку: " + returns.size()
                + (alreadyOpen > 0
                        ? " (уже было открыто: " + alreadyOpen + ")"
                        : "");
    }

    /*
     * Снятые строки в порядке маршрута: дата переделки выдаётся по
     * seq, чтобы следующий по техпроцессу этап не оказался запланирован
     * раньше переделываемого.
     */
    private static List<OrderScheduleRow> reworkOrder(
            Map<Long, OrderScheduleRow> unwritten
    ) {
        List<OrderScheduleRow> ordered = new ArrayList<>(unwritten.values());

        ordered.sort((a, b) -> {
            int seq = Integer.compare(seqOf(a), seqOf(b));

            return seq != 0
                    ? seq
                    : Long.compare(idOf(a), idOf(b));
        });

        return ordered;
    }

    /*
     * Дата переделки: ближайший рабочий день, а для каждой следующей
     * по маршруту строки того же изделия — следующий рабочий день за
     * предыдущей.
     *
     * Прежняя дата плана в расчёте не участвует: она уже принята и
     * снята, и оставлять переделку под ней нельзя — строка с прошедшей
     * датой выпала бы из горизонта распределения и мастер бы её не
     * увидел. Старая дата сохраняется в самом возврате.
     */
    private LocalDate reworkDate(int indexInInstance) {
        LocalDate start = workingDays.nextWorkingDay(LocalDate.now());

        return indexInInstance == 0
                ? start
                : workingDays.plus(start, indexInInstance);
    }

    private static int seqOf(OrderScheduleRow row) {
        return row.getSeq() == null ? 0 : row.getSeq();
    }

    private static long idOf(OrderScheduleRow row) {
        return row.getId() == null ? 0L : row.getId();
    }

    /*
     * Причина возврата БТК по каждой снятой строке — те же строки, что
     * цех читает в шапках карточек.
     */
    private Map<Long, String> controlReasonsOf(Collection<Long> rowIds) {

        if (rowIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> reasons = new LinkedHashMap<>();

        for (ControlReturn ret :
                controlReturnRepository.findOpenByRowIds(rowIds)) {

            OrderScheduleRow row = ret.getScheduleRow();

            if (row != null && row.getId() != null) {
                reasons.putIfAbsent(row.getId(), ret.getReason());
            }
        }

        return reasons;
    }

    /*
     * Первая непустая причина из снятых строк — запасной вариант, когда
     * открытый возврат БТК по строке отсутствует (строку сняли ранее).
     */
    private static String firstReason(Map<Long, String> reasons) {

        for (String reason : reasons.values()) {
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }

        return null;
    }

    /*
     * Сигнал БТК закрывается решением цеха.
     *
     * Открытый возврат живёт, пока цех не решил, что переделывать.
     * Как только снятие сделано, карточка обязана исчезнуть из раздела
     * — иначе цех возвращал бы изделие самому себе снова и снова.
     * Исполнитель причину не теряет: она читается из ReworkReturn,
     * заведённого на его собственную строку.
     */
    private void closeControlReturns(Collection<OrderScheduleRow> rows,
                                     AppUser supervisor,
                                     LocalDateTime now) {
        List<Long> ids = new ArrayList<>();

        for (OrderScheduleRow row : rows) {
            ids.add(row.getId());
        }

        List<ControlReturn> open =
                controlReturnRepository.findOpenByRowIds(ids);

        if (open.isEmpty()) {
            return;
        }

        for (ControlReturn ret : open) {
            ret.setResolvedAt(now);
            ret.setResolvedBy(supervisor);
        }

        controlReturnRepository.saveAll(open);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Отправить изделие на доработку в предшествующий цех.
     *
     * Снятие касается только работ своего цеха: раз изделие уходит
     * назад по маршруту, свой этап цех перестаёт считать пройденным,
     * а чужие закрытые работы он снимать не вправе — за них отвечает
     * тот, кто их делал.
     */
    @Transactional
    public String sendToPredecessor(List<Long> rowIds,
                                    String reason,
                                    AppUser supervisor) {

        requireSupervisor(supervisor);

        String defect = requireReason(reason);

        List<OrderScheduleRow> chosen = requireRows(rowIds, supervisor);

        List<Long> scope = staffService.unitScope(supervisor);

        /*
         * Экземпляры собираются до снятий: ключ экземпляра читается из
         * строки, а снятие проводится по строкам экземпляра сразу.
         */
        Map<String, OrderScheduleRow> instances = new LinkedHashMap<>();

        for (OrderScheduleRow row : chosen) {
            instances.putIfAbsent(instanceKey(row), row);
        }

        LocalDateTime now = LocalDateTime.now();
        List<ReworkReturn> returns = new ArrayList<>();

        for (OrderScheduleRow sample : instances.values()) {

            for (OrderScheduleRow row : completedRowsOfInstance(sample, scope)) {

                row.setCompletedAt(null);
                row.setCompletedBy(null);
                scheduleRowRepository.save(row);

                ReworkReturn ret = new ReworkReturn();
                ret.setScheduleRow(row);
                ret.setReason(defect);
                ret.setReturnType(ReturnType.PREDECESSOR);
                ret.setStatus(ReworkStatus.IN_REWORK);
                ret.setReturnedBy(supervisor);
                ret.setReturnedAt(now);

                returns.add(ret);
            }
        }

        if (returns.isEmpty()) {
            throw new IllegalStateException(
                    "Нечего возвращать: выбранные экземпляры не проведены"
            );
        }

        reworkReturnRepository.saveAll(returns);

        return "Отправлено в предшествующий цех: " + instances.size()
                + ", снято проведений: " + returns.size();
    }

    /**
     * Закрыть возвраты строки — её провели повторно.
     *
     * Вызывается из {@link WorkshopDistributionService#completeRow}:
     * повторное проведение — единственный способ закрыть переделку, и
     * закрывать её надо ровно тем же фактом, который закрывает строку
     * плана. Иначе возврат висел бы над сделанной работой.
     */
    @Transactional
    public void resolveReturns(Long rowId, AppUser performer) {

        if (rowId == null || performer == null) {
            return;
        }

        List<ReworkReturn> open =
                reworkReturnRepository.findOpenByRowIds(List.of(rowId));

        if (open.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (ReworkReturn ret : open) {
            ret.setStatus(ReworkStatus.COMPLETED);
            ret.setResolvedAt(now);
            ret.setResolvedBy(performer);
        }

        reworkReturnRepository.saveAll(open);
    }

    /* ============================================================
       КАРТОЧКА ЭКЗЕМПЛЯРА
       ============================================================ */

    /**
     * Карточка экземпляра: его строки в подразделениях цеха плюс
     * открытые возвраты этих строк.
     *
     * @return {@code null}, если у цеха к экземпляру нет ни одной работы
     */
    private ReworkReturnCard buildCard(String key,
                                       OrderScheduleRow sample,
                                       List<ControlReturn> openReturns,
                                       List<Long> scope) {

        List<OrderScheduleRow> rows = rowsOfInstance(sample, scope);

        if (rows.isEmpty()) {
            return null;
        }

        List<Long> rowIds = new ArrayList<>();

        for (OrderScheduleRow row : rows) {
            rowIds.add(row.getId());
        }

        ReworkReturnCard card = new ReworkReturnCard();

        card.setInstanceKey(key);
        card.setBaseNumber(sample.getBaseNumber());
        card.setInstanceIndex(sample.getInstanceIndex());
        card.setRows(rows);
        card.setReturns(groupByRow(openReturns));
        card.setOwnReturns(groupByRework(
                reworkReturnRepository.findOpenByRowIds(rowIds)));

        /*
         * Подпись изделия: серийный номер выпуска для ЗНП,
         * номер ячейки для группового ЗНП.
         */
        resolveItemLabel(card, rows);

        return card;
    }

    /**
     * Заполняет itemName и itemNumber карточки.
     *
     * Для обычного ЗНП — серийный номер из {@code resolve},
     * для группового — номер ячейки из {@code resolveAttachedCells}.
     * Наименование изделия берётся из наименования позиции заказа.
     *
     * Экземпляр без внесённого номера остаётся без подписи: цех
     * различает карточки одного заказа именно номером, и выдуманная
     * подпись («экземпляр 1») вводила бы в заблуждение вместо ответа.
     */
    private void resolveItemLabel(ReworkReturnCard card,
                                  List<OrderScheduleRow> rows) {

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<Long, AttachedCellView> cellMap =
                serialService.resolveAttachedCells(rows);

        for (OrderScheduleRow row : rows) {
            ProductionOrderSerialProjection serial =
                    serialMap.get(row.getId());

            if (serial != null) {
                card.setItemNumber(serial.getFullNumber());
                card.setItemName(serial.getDisplayName());
                return;
            }

            AttachedCellView cell = cellMap.get(row.getId());

            if (cell != null) {
                card.setItemNumber(cell.getFullNumber());
                card.setItemName(cell.getDisplayName());
                return;
            }
        }
    }

    /**
     * Все строки экземпляра, закреплённые за подразделениями цеха.
     *
     * Ветвь основания определяет запрос: у заказа покупателя план
     * привязан к нему, у группового ЗНП — к заказу на производство
     * (V56), и ошибочная ветвь вернула бы пустой список.
     */
    private List<OrderScheduleRow> rowsOfInstance(OrderScheduleRow sample,
                                                  List<Long> scope) {

        if (sample.getCustomerOrder() != null
                && sample.getCustomerOrder().getId() != null) {
            return scheduleRowRepository.findRowsOfInstanceInUnits(
                    sample.getCustomerOrder().getId(),
                    sample.getItemPosition(),
                    sample.getInstanceIndex(),
                    scope
            );
        }

        if (sample.getProductionOrder() != null
                && sample.getProductionOrder().getId() != null) {
            return scheduleRowRepository
                    .findRowsOfProductionOrderInstanceInUnits(
                            sample.getProductionOrder().getId(),
                            sample.getItemPosition(),
                            sample.getInstanceIndex(),
                            scope
                    );
        }

        return List.of();
    }

    /**
     * Проведённые строки экземпляра, закреплённые за подразделениями
     * цеха, — то, с чего снимается факт при отправке в предшествующий
     * цех.
     *
     * Берутся из той же выборки, что и карточка: чужие закрытые работы
     * цех не видит и снять их не может, поэтому отдельный запрос по
     * всем подразделениям здесь был бы не только лишним, но и неверным.
     */
    private List<OrderScheduleRow> completedRowsOfInstance(
            OrderScheduleRow sample,
            List<Long> scope
    ) {
        List<OrderScheduleRow> completed = new ArrayList<>();

        for (OrderScheduleRow row : rowsOfInstance(sample, scope)) {
            if (row.getCompletedAt() != null) {
                completed.add(row);
            }
        }

        return completed;
    }

    /**
     * Проведённые строки того же экземпляра и цеха, идущие в маршруте
     * после отмеченной.
     *
     * Граница — весь scope руководителя, а не подразделение строки:
     * у службы качества работы лежат в двух подразделениях, и сужение
     * до одного оставило бы переделку висеть под закрытой работой
     * соседнего подразделения того же цеха.
     */
    private List<OrderScheduleRow> followingCompletedRows(OrderScheduleRow row,
                                                          List<Long> scope) {

        List<OrderScheduleRow> following = new ArrayList<>();

        for (OrderScheduleRow other : rowsOfInstance(row, scope)) {

            if (other.getId().equals(row.getId())
                    || other.getCompletedAt() == null) {
                continue;
            }

            if (other.getSeq() != null
                    && row.getSeq() != null
                    && other.getSeq() <= row.getSeq()) {
                continue;
            }

            following.add(other);
        }

        return following;
    }

    private static Map<Long, List<ControlReturn>> groupByRow(
            List<ControlReturn> returns
    ) {
        Map<Long, List<ControlReturn>> grouped = new LinkedHashMap<>();

        for (ControlReturn ret : returns) {
            grouped
                    .computeIfAbsent(
                            ret.getScheduleRow().getId(),
                            key -> new ArrayList<>()
                    )
                    .add(ret);
        }

        return grouped;
    }

    private static Map<Long, List<ReworkReturn>> groupByRework(
            List<ReworkReturn> returns
    ) {
        Map<Long, List<ReworkReturn>> grouped = new LinkedHashMap<>();

        for (ReworkReturn ret : returns) {
            grouped
                    .computeIfAbsent(
                            ret.getScheduleRow().getId(),
                            key -> new ArrayList<>()
                    )
                    .add(ret);
        }

        return grouped;
    }

    /* ============================================================
       ПРОВЕРКИ
       ============================================================ */

    private static final int REASON_LIMIT = 500;

    /**
     * Причина обязательна: цех снимает проведённую работу, и без
     * указания, что именно не так, исполнитель не знает, что переделывать.
     */
    private static String requireReason(String reason) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Укажите причину возврата — исполнитель обязан видеть, что переделывать"
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

    /**
     * Раздел открыт руководителям подразделений: переделку назначает
     * тот, кто распределяет работу. Исполнитель выбирает операции
     * только у себя и этим разделом не владеет.
     */
    private static void requireSupervisor(AppUser user) {

        if (user == null || user.getRole() == null || !isSupervisorRole(user.getRole())) {
            throw new IllegalStateException(
                    "Возвращать работу на доработку вправе только руководитель подразделения"
            );
        }
    }

    private static boolean isSupervisorRole(Role role) {
        return role.name().endsWith("_MASTER");
    }

    /**
     * Отмеченные строки: они существуют и принадлежат тому же праву,
     * что распределение работ подразделения.
     */
    private List<OrderScheduleRow> requireRows(List<Long> rowIds,
                                               AppUser supervisor) {

        if (rowIds == null || rowIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Отметьте операции, которые возвращаете на доработку"
            );
        }

        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(rowIds));
        List<OrderScheduleRow> rows = scheduleRowRepository.findAllById(ids);

        if (rows.size() != ids.size()) {
            throw new IllegalArgumentException(
                    "Часть отмеченных строк уже не найдена — обновите список"
            );
        }

        List<Long> scope = staffService.unitScope(supervisor);

        for (OrderScheduleRow row : rows) {
            if (row.getOrganizationUnit() == null
                    || row.getOrganizationUnit().getId() == null
                    || !scope.contains(row.getOrganizationUnit().getId())) {
                throw new IllegalStateException(
                        "Возвращать на доработку можно работы своего подразделения"
                );
            }
        }

        return rows;
    }

    /*
     * Ключ экземпляра — тот же, что у ControlReturnService: пара
     * «основание плана + позиция + номер внутри позиции». Разные ключи
     * в двух сервисах разошлись бы карточками на одно изделие.
     */
    private static String instanceKey(OrderScheduleRow row) {

        if (row.getCustomerOrder() != null
                && row.getCustomerOrder().getId() != null) {
            return "co:" + row.getCustomerOrder().getId()
                    + ":" + positionOf(row)
                    + ":" + indexOf(row);
        }

        if (row.getProductionOrder() != null
                && row.getProductionOrder().getId() != null) {
            return "po:" + row.getProductionOrder().getId()
                    + ":" + positionOf(row)
                    + ":" + indexOf(row);
        }

        return "row:" + row.getId();
    }

    private static int positionOf(OrderScheduleRow row) {
        return row.getItemPosition() == null ? 0 : row.getItemPosition();
    }

    private static int indexOf(OrderScheduleRow row) {
        return row.getInstanceIndex() == null ? 0 : row.getInstanceIndex();
    }
}
