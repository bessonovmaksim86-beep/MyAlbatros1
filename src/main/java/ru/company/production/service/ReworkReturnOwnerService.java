package ru.company.production.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ReworkReturn;
import ru.company.production.entity.ReworkReturn.ReturnType;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ReworkReturnRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Кто владеет решением о доработке — последний непрерывный участок
 * цеха перед точкой возврата.
 *
 * <p>Маршрут изделия читается как череда участков одного подразделения,
 * разделённых чужими работами. Возврат с контроля БТК касается изделия
 * целиком, но решать, что переделывать, обязан тот цех, чей участок
 * идёт последним перед контролем: он держал изделие непосредственно до
 * того, как его завернули. Участки того же цеха, лежащие раньше чужого
 * цеха, в первой волне не видны — до них дело дойдёт, когда изделие
 * вернётся назад по маршруту.
 *
 * <p>Разделителями участков считаются подразделения складов и контроля
 * (службы SGD, SCD, SQ): они не переделывают работу цеха. Если
 * непосредственно перед владельцем стоит склад — передать изделие назад
 * некому, и кнопка «Отправить в предшествующий цех» неактивна.
 *
 * <p>Решение владельца двигает очередь назад: отметив свои операции на
 * переделку своими силами, цех забирает изделие себе (карточка уходит из
 * раздела, пока переделка не проведена). Отправив изделие в
 * предшествующий цех, цех снимает с себя решение — владельцем становится
 * предыдущий участок, и так до начала маршрута.
 */
@Service
@RequiredArgsConstructor
public class ReworkReturnOwnerService {

    private final OrderScheduleRowRepository scheduleRowRepository;
    private final ControlReturnRepository controlReturnRepository;
    private final ReworkReturnRepository reworkReturnRepository;

    /**
     * Службы, чьи подразделения не переделывают работу цеха.
     *
     * Правило держится здесь и переиспользуется блокировкой проведения
     * (см. {@link InstanceReworkService}): склад и контроль стоят в
     * маршруте как барьеры, и сигнал, заведённый на их строку, не имеет
     * собственной переделки — закрыть его повторным проведением цех не
     * может, потому что не проводил эту работу.
     */
    public static final Set<String> BARRIER_SERVICES = Set.of("SGD", "SCD", "SQ");

    /** Службы, чьи подразделения — склады (передавать назад некому). */
    private static final Set<String> WAREHOUSE_SERVICES = Set.of("SGD", "SCD");

    /**
     * Владение изделием: участок-владелец и его предшественник.
     *
     * Поля пакетно-доступны: заполняет {@link #compute}, читают
     * сервисы раздела; наружу — только геттеры {@code @Getter}.
     */
    @Getter
    public static class Ownership {

        /** Строки участка-владельца (подряд идущие строки одного цеха). */
        List<OrderScheduleRow> ownerRows = List.of();

        /** Подразделение владельца — карточку показывают только ему. */
        Long ownerUnitId;

        /** Предшественник владельца в маршруте. */
        Predecessor predecessor = Predecessor.NONE;

        /** Подразделение предшествующего цеха — цель отправки назад. */
        Long predecessorUnitId;

        /** Цех уже забрал изделие себе (открытая внутренняя переделка). */
        boolean takenInternally;

        public boolean hasOwner() {
            return !ownerRows.isEmpty();
        }

        /** Владелец принадлежит указанным подразделениям. */
        public boolean isOwnedBy(Collection<Long> unitIds) {
            return ownerUnitId != null && unitIds != null
                    && unitIds.contains(ownerUnitId);
        }

        /** Изделие можно отправить в предшествующий цех. */
        public boolean canSendToPredecessor() {
            return predecessor == Predecessor.WORKSHOP
                    && predecessorUnitId != null;
        }

        /** Предшественник — склад: передавать некому, кнопка неактивна. */
        public boolean predecessorIsWarehouse() {
            return predecessor == Predecessor.WAREHOUSE;
        }
    }

    /** Что стоит непосредственно перед участком-владельцем. */
    public enum Predecessor {
        /** Предшествующий цех — изделие можно отправить назад. */
        WORKSHOP,
        /** Предшественник — склад: переделку он не выполняет, кнопка неактивна. */
        WAREHOUSE,
        /** Предшественника нет (владелец первый) или это контроль. */
        NONE
    }

    /**
     * Владение экземпляром по одной его строке.
     *
     * Маршрут, сигналы БТК и возвраты цеха читаются по всему
     * экземпляру, а не по подразделениям руководителя: чтобы увидеть
     * границу участка, нужен маршрут целиком — фильтр по цеху скрыл бы
     * соседние участки и границу участка исказил.
     */
    @Transactional(readOnly = true)
    public Ownership resolve(OrderScheduleRow sample) {

        if (sample == null) {
            return new Ownership();
        }

        List<OrderScheduleRow> route = routeOf(sample);

        if (route.isEmpty()) {
            return new Ownership();
        }

        Set<Long> signaled = new LinkedHashSet<>();

        for (ControlReturn ret : controlReturnRepository
                .findOpenByRowIds(idsOf(route))) {
            if (ret.getScheduleRow() != null) {
                signaled.add(ret.getScheduleRow().getId());
            }
        }

        Set<Long> internal = new LinkedHashSet<>();
        Set<Long> forwarded = new LinkedHashSet<>();

        for (ReworkReturn ret : reworkReturnRepository
                .findOpenByRowIds(idsOf(route))) {

            if (ret.getScheduleRow() == null) {
                continue;
            }

            if (ret.getReturnType() == ReturnType.PREDECESSOR) {
                forwarded.add(ret.getScheduleRow().getId());
            } else {
                internal.add(ret.getScheduleRow().getId());
            }
        }

        return compute(route, signaled, internal, forwarded);
    }

    /*
     * Очередь владения считается по участкам маршрута.
     *
     * Участок — подряд идущие строки одного производственного
     * подразделения. Склад и контроль участок разрывают и сами
     * владельцами не бывают.
     *
     * Владелец — последний участок, ещё не вынесший решение: идём с
     * конца маршрута, пропускаем уже отправленные назад участки (у них
     * открытый возврат в предшественника) и останавливаемся на первом
     * участке с открытым сигналом БТК. Участок, забравший изделие на
     * внутреннюю переделку, решение уже принял — карточка исчезает,
     * очередь не двигается назад.
     */
    private static Ownership compute(List<OrderScheduleRow> route,
                                     Set<Long> signaled,
                                     Set<Long> internal,
                                     Set<Long> forwarded) {

        List<Segment> segments = segmentsOf(route);
        Ownership result = new Ownership();

        for (int i = segments.size() - 1; i >= 0; i--) {

            Segment segment = segments.get(i);

            if (segment.barrier) {
                continue;
            }

            if (touches(internal, segment)) {
                // Цех уже взял изделие на переделку своими силами.
                result.takenInternally = true;
                return result;
            }

            if (touches(forwarded, segment)) {
                // Участок отправил изделие назад — решает предшественник.
                continue;
            }

            if (touches(signaled, segment)) {
                result.ownerRows = segment.rows;
                result.ownerUnitId = segment.unitId;

                if (i > 0) {
                    Segment before = segments.get(i - 1);

                    if (!before.barrier) {
                        result.predecessor = Predecessor.WORKSHOP;
                        result.predecessorUnitId = before.unitId;
                    } else if (before.warehouse) {
                        result.predecessor = Predecessor.WAREHOUSE;
                    } else {
                        result.predecessor = Predecessor.NONE;
                    }
                }

                return result;
            }
        }

        return result;
    }

    /*
     * Разбиение маршрута на участки.
     *
     * Строки одного подразделения, идущие подряд, — один участок.
     * Смена подразделения или разделитель (склад, контроль) начинают
     * новый участок; разделитель — отдельный сегмент, владельцем не
     * становится.
     */
    private static List<Segment> segmentsOf(List<OrderScheduleRow> route) {
        List<Segment> segments = new ArrayList<>();

        for (OrderScheduleRow row : route) {

            OrganizationUnit unit = row.getOrganizationUnit();

            if (unit == null) {
                continue;
            }

            String service = unit.getService() == null
                    ? null
                    : unit.getService().getCode();

            boolean barrier = service != null && BARRIER_SERVICES.contains(service);
            boolean warehouse = service != null && WAREHOUSE_SERVICES.contains(service);

            if (barrier) {
                segments.add(Segment.barrier(unit.getId(), warehouse));
                continue;
            }

            Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);

            if (last != null && !last.barrier && last.unitId.equals(unit.getId())) {
                last.rows.add(row);
            } else {
                segments.add(Segment.workshop(unit.getId(), row));
            }
        }

        return segments;
    }

    private static boolean touches(Set<Long> rowIds, Segment segment) {

        for (OrderScheduleRow row : segment.rows) {
            if (row.getId() != null && rowIds.contains(row.getId())) {
                return true;
            }
        }

        return false;
    }

    private List<OrderScheduleRow> routeOf(OrderScheduleRow sample) {

        if (sample.getCustomerOrder() != null
                && sample.getCustomerOrder().getId() != null) {
            return scheduleRowRepository.findFullRouteOfInstance(
                    sample.getCustomerOrder().getId(),
                    sample.getItemPosition(),
                    sample.getInstanceIndex()
            );
        }

        if (sample.getProductionOrder() != null
                && sample.getProductionOrder().getId() != null) {
            return scheduleRowRepository.findFullRouteOfProductionOrderInstance(
                    sample.getProductionOrder().getId(),
                    sample.getItemPosition(),
                    sample.getInstanceIndex()
            );
        }

        return List.of(sample);
    }

    private static List<Long> idsOf(List<OrderScheduleRow> rows) {
        List<Long> ids = new ArrayList<>();

        for (OrderScheduleRow row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }

        return ids;
    }

    /*
     * Участок маршрута: либо подряд идущие строки одного цеха, либо
     * разделитель (склад/контроль), не способный владеть изделием.
     */
    private static final class Segment {

        final Long unitId;
        final boolean barrier;
        final boolean warehouse;
        final List<OrderScheduleRow> rows = new ArrayList<>();

        private Segment(Long unitId, boolean barrier, boolean warehouse) {
            this.unitId = unitId;
            this.barrier = barrier;
            this.warehouse = warehouse;
        }

        static Segment workshop(Long unitId, OrderScheduleRow row) {
            Segment segment = new Segment(unitId, false, false);
            segment.rows.add(row);
            return segment;
        }

        static Segment barrier(Long unitId, boolean warehouse) {
            return new Segment(unitId, true, warehouse);
        }
    }
}
