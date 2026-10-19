package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ControlParamView;
import ru.company.production.dto.ControlParamValueForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ControlParam;
import ru.company.production.entity.ControlParamType;
import ru.company.production.entity.ControlParamValue;
import ru.company.production.entity.OperationControlParam;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.TechProcessOperationControlParam;
import ru.company.production.repository.OperationControlParamRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationControlParamRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Контролируемые параметры при проведении операции.
 *
 * Технолог задаёт в маршруте набор контролируемых параметров операции
 * и их обязательность, а значение вносит исполнитель: вариант из
 * справочника, текст по условию техпроцесса или подтверждение того,
 * что фактическая ячейка экземпляра относится к требуемому
 * классификатору. Поэтому перед проведением исполнитель обязан
 * отметить каждый параметр операции, а обязательный не может остаться
 * пустым.
 *
 * Завершение строки графика не дублируется: проведение выполняет
 * {@link WorkshopDistributionService#completeRow}, который проверяет
 * право исполнителя, повторность и незакрытых предшественников
 * маршрута. Снятие этих проверок ради отдельной формы оставило бы
 * возможность закрыть чужую или преждевременную работу.
 */
@Service
@RequiredArgsConstructor
public class OperationControlParamService {

    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final OrderScheduleRowRepository scheduleRowRepo;
    private final TechProcessOperationControlParamRepository tpOcpRepo;
    private final OperationControlParamRepository ocpRepo;
    private final SerialProductionOrderRepository serialLinkRepo;
    private final ProductionOrderItemRepository productionItemRepo;
    private final ProductSerialRepository productSerialRepo;
    private final WorkshopDistributionService distributionService;

    /**
     * Есть ли у операции строки графика контролируемые параметры.
     *
     * Нужен обычному проведению без формы: у операции с параметрами
     * кнопка «Провести» обязана вести на форму, иначе исполнитель
     * закрыл бы работу, ничего не отметив.
     */
    @Transactional(readOnly = true)
    public boolean hasControlParams(Long rowId) {
        return !findNodeParams(requireRow(rowId)).isEmpty();
    }

    /**
     * Контролируемые параметры строки графика для формы проведения.
     */
    @Transactional(readOnly = true)
    public List<ControlParamView> loadForRow(Long rowId) {
        OrderScheduleRow row = requireRow(rowId);

        List<TechProcessOperationControlParam> links =
                findNodeParams(row);

        if (links.isEmpty()) {
            return List.of();
        }

        ProductSerial serial = findSerial(row);
        ProductClassifier actualClassifier = classifierOf(serial);
        String actualNumber = serial == null ? null : serial.getFullNumber();

        List<ControlParamView> result = new ArrayList<>();

        for (TechProcessOperationControlParam link : links) {
            result.add(toView(link, actualNumber, actualClassifier));
        }

        return result;
    }

    /**
     * Проведение экземпляра с отметкой контролируемых параметров.
     *
     * Проверяются все параметры операции, а не только пришедшие в
     * форме: исполнитель обязан отметить каждый. Значение обязательного
     * параметра не может быть пустым, а ячейка обязана соответствовать
     * классификатору, заданному технологом, — несоответствие блокирует
     * проведение независимо от обязательности, потому что это условие
     * техпроцесса, а не формальность формы.
     *
     * Факты сохраняются только после того, как проведение прошло все
     * свои проверки: иначе отказ оставил бы в базе отмеченные значения
     * у незакрытой работы, и повторная попытка добавила бы второй набор.
     */
    @Transactional
    public void completeWithControlParams(
            Long rowId,
            AppUser user,
            List<ControlParamValueForm> values
    ) {
        OrderScheduleRow row = requireRow(rowId);

        List<TechProcessOperationControlParam> links =
                findNodeParams(row);

        if (links.isEmpty()) {
            distributionService.completeRow(rowId, user);
            return;
        }

        ProductSerial serial = findSerial(row);
        ProductClassifier actualClassifier = classifierOf(serial);

        Map<Long, ControlParamValueForm> submitted = indexByParam(values);

        for (TechProcessOperationControlParam link : links) {
            validate(link, submitted.get(link.getId()),
                    actualClassifier, serial);
        }

        distributionService.completeRow(rowId, user);

        LocalDateTime moment = LocalDateTime.now();

        for (TechProcessOperationControlParam link : links) {
            ControlParamValueForm form = submitted.get(link.getId());

            if (!isMarked(link, form, serial)) {
                /*
                 * Пустая отметка необязательного параметра — не факт
                 * контроля: в журнале остаётся только то, что
                 * исполнитель действительно внёс или подтвердил.
                 */
                continue;
            }

            ocpRepo.save(toFact(link, form, row, actualClassifier, moment));
        }
    }

    /*
     * Строка графика по идентификатору.
     *
     * Идентификатор приходит из формы, поэтому отсутствие строки —
     * не исключение контроллера, а обычная ошибка запроса.
     */
    private OrderScheduleRow requireRow(Long rowId) {
        if (rowId == null) {
            throw new IllegalArgumentException("Экземпляр не указан");
        }

        return scheduleRowRepo.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Строка графика не найдена"
                ));
    }

    /*
     * Параметры маршрута для операции строки графика.
     *
     * Строка хранит пару «техпроцесс + операция», а не ссылку на узел
     * маршрута, поэтому узел ищется по этой паре.
     */
    private List<TechProcessOperationControlParam> findNodeParams(
            OrderScheduleRow row
    ) {
        if (row.getTechProcess() == null || row.getOperation() == null) {
            return List.of();
        }

        return tpOcpRepo.findForOperationNode(
                row.getTechProcess().getId(),
                row.getOperation().getId()
        );
    }

    /*
     * Проверка отметки одного параметра.
     */
    private void validate(
            TechProcessOperationControlParam link,
            ControlParamValueForm form,
            ProductClassifier actualClassifier,
            ProductSerial serial
    ) {
        ControlParam param = link.getControlParam();
        ControlParamView.Mode mode = modeOf(param);

        /*
         * Необязательный параметр вправе остаться пустым — и тогда
         * отметка его не требуется вовсе: исполнитель проводит работу,
         * не трогая то, что техпроцесс не требует. Проверять
         * соответствие ячейки при пустой отметке незачем, а вот если
         * отметку поставили, она обязана быть верной: подтверждение
         * ложному соответствию не повод.
         */
        if (form == null) {
            if (link.isMandatory()) {
                throw new IllegalStateException(
                        "Не отмечен обязательный контролируемый параметр «"
                                + param.getName() + "»"
                );
            }

            return;
        }

        /*
         * Соответствие ячейки проверяет сервер, а не подтверждает
         * исполнитель: галка «ячейка подходит» при несовпадении была
         * бы либо ложью, либо блокированной кнопкой без объяснения
         * причины. Поэтому здесь остаётся одно условие техпроцесса —
         * фактический классификатор обязан оказаться требуемым.
         *
         * Незнакомая ячейка (её ещё не внёс склад) снимается с
         * необязательного параметра: сверять нечего, а блокировать
         * работу из-за справочной задержки незачем. Обязательный
         * параметр такой случай держит — без номера ячейки контроль
         * не выполнен вовсе.
         */
        if (mode == ControlParamView.Mode.CELL) {
            if (serial == null) {
                if (link.isMandatory()) {
                    throw new IllegalStateException(
                            "Параметр «" + param.getName()
                                    + "» требует номер ячейки, а у экземпляра "
                                    + "он ещё не внесён"
                    );
                }

                return;
            }

            requireClassifierMatches(param, link, actualClassifier);

            return;
        }

        boolean filled = mode == ControlParamView.Mode.LIST
                ? form.getValueId() != null
                : form.getText() != null && !form.getText().isBlank();

        if (link.isMandatory() && !filled) {
            throw new IllegalStateException(
                    "Не заполнен обязательный параметр «"
                            + param.getName() + "»"
            );
        }

        /*
         * Выбранный вариант обязан принадлежать этому параметру:
         * идентификатор приходит из формы, и без проверки исполнитель
         * отметил бы значение другого параметра.
         */
        if (mode == ControlParamView.Mode.LIST
                && form.getValueId() != null
                && !ownsValue(param, form.getValueId())) {

            throw new IllegalStateException(
                    "Выбранное значение не относится к параметру «"
                            + param.getName() + "»"
            );
        }
    }

    private void requireClassifierMatches(
            ControlParam param,
            TechProcessOperationControlParam link,
            ProductClassifier actualClassifier
    ) {
        ProductClassifier required = link.getCellClassifier();

        if (required == null) {
            /*
             * Технолог не задал требуемый классификатор — сверять
             * не с чем, и отметка исполнительского соответствия
             * принимается как есть.
             */
            return;
        }

        Long actualId = actualClassifier == null
                ? null
                : actualClassifier.getId();

        if (!required.getId().equals(actualId)) {
            throw new IllegalStateException(
                    "Параметр «" + param.getName()
                            + "»: ячейка относится к классификатору «"
                            + labelOf(actualClassifier)
                            + "», а техпроцесс требует «"
                            + labelOf(required)
                            + "»"
            );
        }
    }

    /*
     * Факт параметра проведённого экземпляра.
     */
    private OperationControlParam toFact(
            TechProcessOperationControlParam link,
            ControlParamValueForm form,
            OrderScheduleRow row,
            ProductClassifier actualClassifier,
            LocalDateTime moment
    ) {
        ControlParam param = link.getControlParam();
        ControlParamView.Mode mode = modeOf(param);

        OperationControlParam fact = new OperationControlParam();

        fact.setScheduleRow(row);
        fact.setControlParam(param);
        fact.setMandatory(link.isMandatory());
        fact.setCompletedAt(moment);

        if (mode == ControlParamView.Mode.LIST
                && form.getValueId() != null) {
            fact.setControlParamValue(valueOf(param, form.getValueId()));
        } else if (mode == ControlParamView.Mode.TEXT) {
            fact.setTextValue(form.getText());
        } else if (mode == ControlParamView.Mode.CELL) {
            /*
             * Вносится и требуемый, и фактический классификатор:
             * факт обязан пережить правку маршрута, иначе по журналу
             * нельзя было бы восстановить, что именно сверяли.
             */
            fact.setCellClassifier(link.getCellClassifier());
            fact.setActualClassifier(actualClassifier);
        }

        return fact;
    }

    private ControlParamView toView(
            TechProcessOperationControlParam link,
            String actualNumber,
            ProductClassifier actualClassifier
    ) {
        ControlParam param = link.getControlParam();
        ControlParamView.Mode mode = modeOf(param);

        return new ControlParamView(
                link.getId(),
                param.getName(),
                mode,
                link.isMandatory(),
                mode == ControlParamView.Mode.LIST
                        ? optionsOf(param)
                        : List.of(),
                link.getTextValue(),
                mode == ControlParamView.Mode.CELL
                        ? idOf(link.getCellClassifier())
                        : null,
                mode == ControlParamView.Mode.CELL
                        ? labelOf(link.getCellClassifier())
                        : null,
                actualNumber,
                mode == ControlParamView.Mode.CELL
                        ? idOf(actualClassifier)
                        : null,
                mode == ControlParamView.Mode.CELL
                        ? labelOf(actualClassifier)
                        : null
        );
    }

    /*
     * Режим заполнения параметра.
     *
     * Ячейка важнее типа: параметр про ячейку выбирается из состава
     * изделия, даже если в справочнике его завели полем ввода — то же
     * правило держит технология маршрута (ControlParam#isCellMode).
     */
    /*
     * Отмечен ли параметр: обязателен — отмечен всегда (пустоту
     * не пропускает валидация), необязателен — только если
     * исполнитель внёс значение.
     *
     * Пустая отметка необязательного параметра — не факт контроля:
     * в журнале остаётся только то, что исполнитель действительно
     * внёс.
     */
    private boolean isMarked(
            TechProcessOperationControlParam link,
            ControlParamValueForm form,
            ProductSerial serial
    ) {
        if (form == null) {
            return false;
        }

        if (link.isMandatory()) {
            return true;
        }

        ControlParamView.Mode mode = modeOf(link.getControlParam());

        if (mode == ControlParamView.Mode.CELL) {
            return serial != null;
        }

        return mode == ControlParamView.Mode.LIST
                ? form.getValueId() != null
                : form.getText() != null && !form.getText().isBlank();
    }

    private ControlParamView.Mode modeOf(ControlParam param) {
        if (param.isCellMode()) {
            return ControlParamView.Mode.CELL;
        }

        return param.getType() == ControlParamType.TEXT_INPUT
                ? ControlParamView.Mode.TEXT
                : ControlParamView.Mode.LIST;
    }

    private List<ControlParamView.Option> optionsOf(ControlParam param) {
        List<ControlParamView.Option> options = new ArrayList<>();

        for (ControlParamValue value : param.getValues()) {
            options.add(new ControlParamView.Option(
                    value.getId(), value.getValue()));
        }

        return options;
    }

    private boolean ownsValue(ControlParam param, Long valueId) {
        return valueOf(param, valueId) != null;
    }

    private ControlParamValue valueOf(ControlParam param, Long valueId) {
        if (valueId == null) {
            return null;
        }

        for (ControlParamValue value : param.getValues()) {
            if (valueId.equals(value.getId())) {
                return value;
            }
        }

        return null;
    }

    private static Map<Long, ControlParamValueForm> indexByParam(
            List<ControlParamValueForm> values
    ) {
        Map<Long, ControlParamValueForm> byParam = new HashMap<>();

        if (values != null) {
            for (ControlParamValueForm form : values) {
                if (form != null && form.getParamId() != null) {
                    byParam.putIfAbsent(form.getParamId(), form);
                }
            }
        }

        return byParam;
    }

    /*
     * Изделие экземпляра операции.
     *
     * У строки плана серийного номера нет (V43): он появляется в
     * составе выпуска. Для выпуска экземпляр — строка выпуска той же
     * позиции заказа в том же порядке, для группового ЗНП — ячейка,
     * внесённая складом, в порядке внесения. Тот же порядок
     * сопоставления держит read-модель распределения, и проведение
     * обязано подписывать работу тем же номером, что показывает
     * список, иначе исполнитель отмечал бы не то изделие.
     */
    private ProductSerial findSerial(OrderScheduleRow row) {
        Integer index = row.getInstanceIndex();

        if (index == null || index < 0) {
            return null;
        }

        if (row.getProductionOrder() != null
                && row.getProductionOrder().getId() != null) {

            List<SerialProductionOrder> links = serialLinkRepo
                    .findByProductionOrderId(
                            row.getProductionOrder().getId());

            return index < links.size()
                    ? links.get(index).getSerial()
                    : null;
        }

        if (row.getCustomerOrder() == null
                || row.getCustomerOrder().getId() == null
                || row.getItemPosition() == null) {
            return null;
        }

        List<ProductionOrderSerialProjection> serials =
                productionItemRepo.findSerialsByCustomerOrderIds(
                        Set.of(row.getCustomerOrder().getId()));

        int seen = 0;

        for (ProductionOrderSerialProjection projection : serials) {
            if (!row.getItemPosition().equals(projection.getItemPosition())
                    || projection.getSerialId() == null) {
                continue;
            }

            if (seen == index) {
                Long serialId = projection.getSerialId();

                return productSerialRepo.findById(serialId).orElse(null);
            }

            seen++;
        }

        return null;
    }

    private static ProductClassifier classifierOf(ProductSerial serial) {
        return serial == null ? null : serial.getProductClassifier();
    }

    private static Long idOf(ProductClassifier classifier) {
        return classifier == null ? null : classifier.getId();
    }

    private static String labelOf(ProductClassifier classifier) {
        return classifier == null
                ? "не определён"
                : classifier.getCode() + " — "
                  + classifier.getDisplayName();
    }
}
