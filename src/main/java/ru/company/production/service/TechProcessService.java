package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.TechProcessForm;
import ru.company.production.dto.TechProcessGraphView;
import ru.company.production.entity.ControlParam;
import ru.company.production.entity.ControlParamType;
import ru.company.production.entity.ControlParamValue;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessClassifier;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.TechProcessOperationControlParam;
import ru.company.production.entity.WorkPlace;
import ru.company.production.repository.AppliedClassifierView;
import ru.company.production.repository.ClassifierOptionProjection;
import ru.company.production.repository.ClassifierRouteOptionView;
import ru.company.production.repository.ControlParamRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.TechProcessListItemView;
import ru.company.production.repository.WorkPlaceRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TechProcessService {

    /*
     * Продолжительность рабочего дня в часах — используется
     * для перевода длительности маршрута в рабочие дни.
     */
    private static final int WORKDAY_HOURS = 8;

    private final TechProcessRepository techProcessRepository;
    private final TechProcessClassifierRepository linkRepository;
    private final ProductClassifierRepository classifierRepository;
    private final OperationRepository operationRepository;
    private final WorkPlaceRepository workPlaceRepository;
    private final ControlParamRepository controlParamRepository;
    private final TechProcessCodeGenerator codeGenerator;

    @Transactional(readOnly = true)
    public List<TechProcess> findAll() {
        return techProcessRepository.findAllDetailed();
    }

    /*
     * Список для страницы техпроцессов: отдельной строкой на каждое
     * изделие, к которому применён маршрут.
     */
    @Transactional(readOnly = true)
    public List<TechProcessListItemView> findAllWithClassifiers() {
        return techProcessRepository.findAllWithAppliedClassifiers();
    }

    /**
     * Полные данные техпроцесса для отображения 
     * (шапка, изделие, операции с нормами).
     */
    @Transactional(readOnly = true)
    public TechProcess getDetailed(Long id) {
        return getFull(id);
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return techProcessRepository.countByActiveTrue();
    }

    @Transactional(readOnly = true)
    public List<Operation> findActiveOperations() {
        return operationRepository.findAllByOrderByOperationName_NameAsc();
    }

    @Transactional(readOnly = true)
    public List<ProductClassifier> findClassifiers() {
        return classifierRepository.findAllByOrderByCodeAsc();
    }

    /*
     * Справочник рабочих мест для конструктора маршрута.
     * Нужны только id и подпись (код + наименование) — оба не lazy.
     */
    @Transactional(readOnly = true)
    public List<WorkPlace> findActiveWorkPlaces() {
        return workPlaceRepository.findByActiveTrueOrderByCodeAsc();
    }

    public List<ClassifierOptionProjection> findClassifierOptions() {
        return classifierRepository.findAllOptions();
    }

    /*
     * Справочник контролируемых параметров для конструктора маршрута:
     * виды параметров вместе с их вариантами. Форма маршрута только
     * читает его — ведёт справочник технолог в разделе
     * «Контролируемые параметры».
     */
    @Transactional(readOnly = true)
    public List<ControlParam> findControlParams() {
        return controlParamRepository.findAllDetailed();
    }

    /**
     * Ячейки, входящие в изделие техпроцесса.
     *
     * Нужны форме техпроцесса как источник значений для параметра
     * «…ячейки…»: список строит шаблон, а open-in-view выключен,
     * поэтому состав читают здесь, а не из ленивой коллекции
     * классификатора.
     */
    @Transactional(readOnly = true)
    public List<ProductClassifier> findCellOptions(Long classifierId) {
        if (classifierId == null) {
            return List.of();
        }

        return classifierRepository.findCellOptionsByOwner(classifierId);
    }

    public String nextCode() {
        return codeGenerator.next(techProcessRepository.findAllCodes());
    }

    /**
     * Форма для создания или повторного отображения после ошибки.
     */
    @Transactional(readOnly = true)
    public TechProcessForm getForm(Long id) {
        TechProcess process = getFull(id);

        TechProcessForm form = new TechProcessForm();
        form.setId(process.getId());
        form.setCode(process.getCode());
        form.setNote(process.getNote());
        form.setVersion(process.getVersion());
        form.setProductClassifierId(
                process.getProductClassifier().getId()
        );

        for (TechProcessOperation node : process.getOperations()) {
            TechProcessForm.TechProcessOperationForm row =
                    new TechProcessForm.TechProcessOperationForm();

            row.setOperationId(node.getOperation().getId());
            row.setLaborHours(node.getLaborHours());
            row.setMachineHours(node.getMachineHours());
            row.setDailyLimit(node.getDailyLimit());
            row.setNote(node.getNote());

            /*
             * Операция может быть привязана к нескольким рабочим
             * местам — передаём в форму все выбранные.
             */
            List<Long> placeIds = new ArrayList<>();

            for (WorkPlace place : node.getWorkPlaces()) {
                placeIds.add(place.getId());
            }

            row.setWorkPlaceIds(placeIds);

            List<Long> predecessors = new ArrayList<>();

            for (TechProcessOperation predecessor
                    : node.getPredecessors()) {
                predecessors.add(predecessor.getOperation().getId());
            }

            row.setPredecessorOperationIds(predecessors);

            /*
             * Контролируемые параметры: в форму уходят идентификаторы
             * вида и значения из справочника плюс отметка
             * обязательности.
             *
             * У параметра типа TEXT_INPUT варианта в справочнике нет,
             * и в форму вместо него уходит вписанный текст — иначе
             * обращение к controlParamValue дало бы NPE.
             *
             * У режима CELL_LIST ячейка хранится в cellClassifier, и
             * её ID уходит в форму — сервер вернёт её же при повторном
             * открытии.
             */
            for (TechProcessOperationControlParam param
                    : node.getControlParams()) {
                TechProcessForm.ControlParamRow paramRow =
                        new TechProcessForm.ControlParamRow();

                paramRow.setControlParamId(
                        param.getControlParam().getId()
                );

                if (param.getControlParamValue() != null) {
                    paramRow.setControlParamValueId(
                            param.getControlParamValue().getId()
                    );
                } else if (param.getCellClassifier() != null) {
                    paramRow.setCellClassifierId(
                            param.getCellClassifier().getId()
                    );
                } else {
                    paramRow.setTextValue(param.getTextValue());
                }

                paramRow.setMandatory(param.isMandatory());

                row.getControlParams().add(paramRow);
            }

            form.getOperations().add(row);
        }

        return form;
    }

    @Transactional
    public TechProcess create(TechProcessForm form) {
        if (techProcessRepository.existsByCode(normalizeCode(form))) {
            throw new IllegalArgumentException(
                    "Техпроцесс с таким кодом уже существует"
            );
        }

        TechProcess process = new TechProcess();
        apply(form, process);

        return save(process);
    }

    @Transactional
    public TechProcess update(Long id, TechProcessForm form) {
        TechProcess process = getFull(id);

        if (process.getVersion() != null
                && !process.getVersion().equals(form.getVersion())) {
            throw new IllegalStateException(
                    "Запись уже изменена другим пользователем. "
                            + "Обновите страницу и повторите операцию."
            );
        }

        if (techProcessRepository.existsByCodeAndIdNot(
                normalizeCode(form), id)) {
            throw new IllegalArgumentException(
                    "Техпроцесс с таким кодом уже существует"
            );
        }

        process.clearOperations();
        techProcessRepository.flush();

        apply(form, process);

        return save(process);
    }

    @Transactional
    public void delete(Long id) {
        TechProcess process = getFull(id);

        try {
            techProcessRepository.delete(process);
            techProcessRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException(
                    "Невозможно удалить техпроцесс",
                    exception
            );
        }
    }

    /**
     * Применяет существующий техпроцесс к другому изделию:
     * маршрут (операции, нормы, зависимости, рабочие места) не
     * дублируется, к техпроцессу просто добавляется ещё одно изделие.
     */
    @Transactional
    public TechProcess applyToClassifier(
            Long processId,
            Long targetClassifierId,
            boolean asBasic
    ) {
        TechProcess process = techProcessRepository
                .findWithAppliedClassifiers(processId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Техпроцесс с идентификатором "
                                + processId
                                + " не найден"
                ));

        ProductClassifier target = classifierRepository
                .findById(targetClassifierId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Изделие классификатора не найдено"
                ));

        boolean alreadyApplied = process.getClassifierLinks()
                .stream()
                .anyMatch(
                        link -> link.getClassifier().getId()
                                .equals(target.getId())
                );

        if (alreadyApplied) {
            throw new IllegalArgumentException(
                    "Техпроцесс уже применён к выбранному изделию"
            );
        }

        /*
         * Базовый маршрут у изделия один, поэтому прежнюю отметку
         * снимают до добавления новой. Иначе изделие получило бы два
         * «основных» маршрута, и порядок в списке выбора стал бы
         * зависеть от порядка строк в базе.
         */
        if (asBasic) {
            linkRepository.clearBasicForClassifier(target.getId());
        }

        process.getClassifierLinks().add(
                new TechProcessClassifier(process, target, asBasic)
        );

        try {
            return techProcessRepository.saveAndFlush(process);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "Не удалось применить техпроцесс к изделию",
                    exception
            );
        }
    }

    /**
     * Делает техпроцесс базовым для изделия.
     *
     * Отметку переносят: у изделия остаётся один основной маршрут.
     * Сам маршрут при этом не меняют — изделие просто начинает
     * выпускаться по выбранному маршруту по умолчанию.
     */
    @Transactional
    public TechProcess makeBasicForClassifier(
            Long processId,
            Long classifierId
    ) {
        TechProcessClassifier link = linkRepository
                .findByTechProcess_IdAndClassifier_Id(
                        processId,
                        classifierId
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "Техпроцесс не применён к выбранному изделию"
                ));

        if (link.isBasic()) {
            return link.getTechProcess();
        }

        linkRepository.clearBasicForClassifier(classifierId);

        link.setBasic(true);
        linkRepository.saveAndFlush(link);

        return link.getTechProcess();
    }

    /**
     * Техпроцессы изделия для выбора маршрута: базовый первым,
     * остальные — по коду.
     */
    @Transactional(readOnly = true)
    public List<ClassifierRouteOptionView> findRoutesForClassifier(
            Long classifierId
    ) {
        if (classifierId == null) {
            return List.of();
        }

        return techProcessRepository
                .findRoutesForClassifier(classifierId);
    }

    /**
     * Возвращает изделия, к которым применён техпроцесс
     * (для отображения в списке и на странице применения).
     */
    @Transactional(readOnly = true)
    public List<AppliedClassifierView> findAppliedOptions(Long id) {
        boolean exists = techProcessRepository
                .findWithAppliedClassifiers(id)
                .isPresent();

        if (!exists) {
            throw new EntityNotFoundException(
                    "Техпроцесс с идентификатором " + id + " не найден"
            );
        }

        return linkRepository.findAppliedClassifiers(id);
    }

    /**
     * Строит модель для визуализации маршрута:
     * узлы по уровням топологического порядка и рёбра зависимостей.
     */
    @Transactional(readOnly = true)
    public TechProcessGraphView buildGraph(Long id) {
        TechProcess process = getFull(id);

        /*
         * LinkedHashSet убирает возможные повторы одной и той же
         * операции: они возникают, когда запрос подгружает сразу
         * несколько коллекций. В графе операция показывается один раз.
         */
        List<TechProcessOperation> nodes = new ArrayList<>(
                new LinkedHashSet<>(process.getOperations())
        );

        List<List<Integer>> adjacency =
                buildAdjacency(nodes, new int[nodes.size()]);

        int[] level = levels(nodes, adjacency);

        /*
         * Порядок узлов внутри колонки подбирается так, чтобы
         * стрелки между операциями минимально пересекались.
         */
        Integer[] order = orderWithinLevels(nodes.size(), level, adjacency);

        TechProcessGraphView view = new TechProcessGraphView();
        view.setProcessId(process.getId());
        view.setCode(process.getCode());
        view.setNote(process.getNote());
        view.setProductDisplayName(
                process.getProductClassifier().getDisplayName()
        );

        BigDecimal totalLabor = BigDecimal.ZERO;
        BigDecimal totalMachine = BigDecimal.ZERO;

        /*
         * Длительность каждой операции в часах — нужна для расчёта
         * критического пути маршрута.
         */
        BigDecimal[] durations = new BigDecimal[nodes.size()];

        for (int rank = 0; rank < order.length; rank++) {
            int i = order[rank];

            TechProcessOperation node = nodes.get(i);
            Operation base = node.getOperation();

            TechProcessGraphView.Node viewNode =
                    new TechProcessGraphView.Node();

            viewNode.setId((long) i);
            viewNode.setLevel(level[i]);
            viewNode.setPosition(node.getPosition());
            viewNode.setName(base.getOperationName().getName());
            viewNode.setTypeName(base.getOperationType().getName());
            viewNode.setServiceName(base.getService().getName());
            viewNode.setOrganizationUnitName(
                    base.getOrganizationUnit() == null
                            ? null
                            : base.getOrganizationUnit().getName()
            );
            viewNode.setExecutorRoleName(
                    base.getExecutorRole().getDisplayName()
            );
            viewNode.setLaborHours(node.getLaborHours());
            viewNode.setMachineHours(node.getMachineHours());
            viewNode.setDailyLimit(node.getDailyLimit());
            viewNode.setNote(node.getNote());
            viewNode.setTypeCode(base.getOperationType().getCode());
            viewNode.setShape(nodeShape(
                    base.getOperationType().getCode()
            ));

            /*
             * Длительность операции: операция считается завершённой,
             * когда окончены и ручная работа, и машинное время,
             * поэтому берётся максимум из двух норм.
             */
            BigDecimal duration = node.getLaborHours().max(
                    node.getMachineHours()
            );

            viewNode.setDurationHours(duration);
            viewNode.setDurationDays(toWorkdays(duration));

            durations[i] = duration;

            for (WorkPlace workPlace : node.getWorkPlaces()) {
                viewNode.getWorkPlaces().add(
                        workPlace.getCode() + " · " + workPlace.getName()
                );
            }

            for (TechProcessOperation predecessor
                    : node.getPredecessors()) {
                viewNode.getDependsOn().add(
                        predecessor.getOperation()
                                .getOperationName()
                                .getName()
                );
            }

            /*
             * Контролируемые параметры в карточке узла: технолог
             * обязан видеть их не входя в маршрут — иначе, наводя
             * курсор на операцию графа, он не узнает, что там есть
             * что контролировать.
             */
            for (TechProcessOperationControlParam param
                    : node.getControlParams()) {
                TechProcessGraphView.Node.ControlParamInfo info =
                        new TechProcessGraphView.Node.ControlParamInfo();

                info.setParamName(
                        param.getControlParam().getName()
                );
                info.setParamValue(controlParamValueLabel(param));
                info.setMandatory(param.isMandatory());

                viewNode.getControlParams().add(info);
            }

            view.getNodes().add(viewNode);
            view.addNode(level[i], viewNode);

            totalLabor = totalLabor.add(node.getLaborHours());
            totalMachine = totalMachine.add(node.getMachineHours());
        }

        List<String> pairs = new ArrayList<>();

        for (int i = 0; i < adjacency.size(); i++) {
            for (int next : adjacency.get(i)) {
                view.getEdges().add(
                        new TechProcessGraphView.Edge((long) i, (long) next)
                );

                pairs.add(i + "-" + next);
            }
        }

        view.setEdgePairs(String.join(",", pairs));

        view.setTotalLaborHours(totalLabor);
        view.setTotalMachineHours(totalMachine);

        /*
         * Критический путь — самая длинная по длительности цепочка
         * операций. Уровень узла построен как длина самой длинной
         * цепочки предшественников, поэтому обход по возрастанию
         * уровня даёт корректный топологический порядок.
         */
        BigDecimal criticalPathHours = criticalPath(
                nodes.size(),
                level,
                durations,
                adjacency
        );

        view.setCriticalPathHours(criticalPathHours);
        view.setCriticalPathDays(toWorkdays(criticalPathHours));
        view.setWorkdayHours(WORKDAY_HOURS);

        return view;
    }

    /*
     * Порядок узлов внутри колонок графа.
     *
     * Узлы одного уровня расставляются по среднему (барицентр)
     * положению их соседей в соседних колонках: чем ближе связанные
     * операции друг к другу, тем меньше стрелок перекашивается и
     * пересекается. Проход делается несколько раз в обе стороны,
     * чтобы порядок уточнился по всему маршруту.
     */
    private static Integer[] orderWithinLevels(
            int size,
            int[] level,
            List<List<Integer>> adjacency
    ) {
        int[] rank = new int[size];

        /* Обратный список смежности: узел -> его предшественники. */
        List<List<Integer>> predecessors = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            predecessors.add(new ArrayList<>());
        }

        for (int i = 0; i < size; i++) {
            for (int next : adjacency.get(i)) {
                predecessors.get(next).add(i);
            }
        }

        Integer[] order = new Integer[size];

        for (int i = 0; i < size; i++) {
            order[i] = i;
        }

        java.util.Arrays.sort(
                order,
                (left, right) -> Integer.compare(level[left], level[right])
        );

        /* Стартовый ранг: узлы идут друг за другом по колонкам. */
        int currentLevel = -1;
        int position = 0;

        for (int index : order) {
            if (level[index] != currentLevel) {
                currentLevel = level[index];
                position = 0;
            }

            rank[index] = position++;
        }

        for (int pass = 0; pass < 4; pass++) {
            boolean forward = pass % 2 == 0;

            List<Integer> sweep = new ArrayList<>();

            for (int index : order) {
                sweep.add(index);
            }

            if (!forward) {
                java.util.Collections.reverse(sweep);
            }

            for (int index : sweep) {
                List<Integer> neighbours = forward
                        ? predecessors.get(index)
                        : adjacency.get(index);

                if (neighbours.isEmpty()) {
                    continue;
                }

                double barycenter = 0;

                for (int neighbour : neighbours) {
                    barycenter += rank[neighbour];
                }

                rank[index] = (int) Math.round(
                        barycenter / neighbours.size()
                );
            }

            /*
             * Ранги внутри колонки не должны перескакивать друг
             * друга: упорядочиваем узлы по полученному барицентру и
             * нумеруем заново без разрывов.
             */
            final int[] rankSnapshot = rank.clone();

            java.util.Arrays.sort(
                    order,
                    (left, right) -> {
                        int byLevel = Integer.compare(
                                level[left],
                                level[right]
                        );

                        if (byLevel != 0) {
                            return byLevel;
                        }

                        int byBarycenter = Integer.compare(
                                rankSnapshot[left],
                                rankSnapshot[right]
                        );

                        return byBarycenter != 0
                                ? byBarycenter
                                : Integer.compare(left, right);
                    }
            );

            currentLevel = -1;
            position = 0;

            for (int index : order) {
                if (level[index] != currentLevel) {
                    currentLevel = level[index];
                    position = 0;
                }

                rank[index] = position++;
            }
        }

        return order;
    }

    /*
     * Форма узла графа по коду типа операции:
     * производство — прямоугольник, передача — параллелограмм,
     * возврат — шестиугольник, распределение работ — ромб,
     * получение комплектации — овал,
     * комплектовочная — пятиугольник,
     * контрольная — восьмиугольник,
     * приёмка на склад — капсула.
     */
    private static String nodeShape(String typeCode) {
        if (typeCode == null) {
            return "rect";
        }

        return switch (typeCode) {
            case "TRANSFER" -> "parallelogram";
            case "RETURN" -> "hexagon";
            case "WORK_DISTRIBUTION" -> "diamond";
            case "KIT_RECEIPT" -> "oval";
            case "KITTING" -> "pentagon";
            case "CONTROL" -> "octagon";
            case "RECEIVING" -> "stadium";
            default -> "rect";
        };
    }

    /*
     * Подпись значения контролируемого параметра для карточки узла.
     *
     * У параметра три способа задать значение (см.
     * TechProcessOperationControlParam): вариант справочника, свободный
     * ввод или входящая ячейка — поэтому подпись собирается по тому же
     * приоритету, что и контрол в конструкторе маршрута. Пустое значение
     * оставляем пустым: технолог зафиксировал сам факт контроля, и
     * выдумывать за него значение граф не вправе.
     */
    private static String controlParamValueLabel(
            TechProcessOperationControlParam param
    ) {
        if (param.getControlParamValue() != null) {
            return param.getControlParamValue().getValue();
        }

        if (param.getTextValue() != null
                && !param.getTextValue().isBlank()) {
            return param.getTextValue();
        }

        if (param.getCellClassifier() != null) {
            return param.getCellClassifier().getDisplayName();
        }

        return null;
    }

    /*
     * Перевод часов в рабочие дни с округлением вверх до десятых:
     * неполный день тоже занимает рабочий день.
     */
    private static BigDecimal toWorkdays(BigDecimal hours) {
        if (hours == null) {
            return BigDecimal.ZERO;
        }

        return hours.divide(
                BigDecimal.valueOf(WORKDAY_HOURS),
                4,
                RoundingMode.HALF_UP
        ).setScale(1, RoundingMode.CEILING);
    }

    /*
     * Часы для отображения: без лишних знаков
     * (2.50 → 2.5, 8.00 → 8).
     */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal stripped = value.stripTrailingZeros();

        return stripped.scale() < 0
                ? stripped.setScale(0)
                : stripped;
    }

    /*
     * Длительность критического пути в часах: для каждого узла
     * earliestFinish = длительность + максимум earliestFinish
     * предшественников.
     */
    private static BigDecimal criticalPath(
            int size,
            int[] level,
            BigDecimal[] durations,
            List<List<Integer>> adjacency
    ) {
        if (size == 0) {
            return BigDecimal.ZERO;
        }

        Integer[] order = new Integer[size];

        for (int i = 0; i < size; i++) {
            order[i] = i;
        }

        java.util.Arrays.sort(
                order,
                (left, right) -> Integer.compare(
                        level[left],
                        level[right]
                )
        );

        BigDecimal[] earliestFinish = new BigDecimal[size];

        for (int index : order) {
            BigDecimal finish = durations[index] == null
                    ? BigDecimal.ZERO
                    : durations[index];

            for (int next : adjacency.get(index)) {
                earliestFinish[next] = earliestFinish[next] == null
                        ? finish
                        : earliestFinish[next].max(finish);
            }
        }

        BigDecimal critical = BigDecimal.ZERO;

        for (int i = 0; i < size; i++) {
            BigDecimal start = earliestFinish[i] == null
                    ? BigDecimal.ZERO
                    : earliestFinish[i];

            BigDecimal finish = start.add(
                    durations[i] == null
                            ? BigDecimal.ZERO
                            : durations[i]
            );

            if (finish.compareTo(critical) > 0) {
                critical = finish;
            }
        }

        return critical;
    }

    private TechProcess getFull(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор техпроцесса"
            );
        }

        return techProcessRepository
                .findFullById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Техпроцесс с идентификатором "
                                + id
                                + " не найден"
                ));
    }

    private void apply(
            TechProcessForm form,
            TechProcess process
    ) {
        ProductClassifier classifier = classifierRepository
                .findById(form.getProductClassifierId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Изделие классификатора не найдено"
                ));

        process.setCode(normalizeCode(form));
        process.setProductClassifier(classifier);
        process.setNote(trim(form.getNote()));
        process.setActive(true);

        /*
         * Основное изделие всегда считается применённым.
         * Сравнение по идентификатору, так как в коллекции может
         * лежать ленивая прокладка того же изделия.
         */
        boolean mainApplied = process.getClassifierLinks()
                .stream()
                .anyMatch(link -> link.getClassifier().getId()
                        .equals(classifier.getId()));

        if (!mainApplied) {
            /*
             * Новый маршрут становится базовым для изделия только
             * когда базового у изделия ещё нет: если изделие уже
             * выпускают по другому маршруту, автоматически «перетягивать»
             * базовость на себя нельзя — выпуск в заказах сменился бы
             * молча. Базовым новый маршрут делает технолог отдельной
             * кнопкой в списке.
             */
            boolean hasBasic = linkRepository
                    .findBasicForClassifier(classifier.getId())
                    .isPresent();

            process.getClassifierLinks().add(
                    new TechProcessClassifier(process, classifier, !hasBasic)
            );
        }

        List<TechProcessForm.TechProcessOperationForm> rows =
                selectedRows(form);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Добавьте в техпроцесс хотя бы одну операцию"
            );
        }

        Set<Long> operationIds = new HashSet<>();

        for (TechProcessForm.TechProcessOperationForm row : rows) {
            if (!operationIds.add(row.getOperationId())) {
                throw new IllegalArgumentException(
                        "Операция повторяется в маршруте"
                );
            }
        }

        Map<Long, Operation> operations = new HashMap<>();

        operationRepository
                .findAllById(operationIds)
                .forEach(operation ->
                        operations.put(operation.getId(), operation));

        if (operations.size() != operationIds.size()) {
            throw new EntityNotFoundException(
                    "Одна из операций не найдена в справочнике"
            );
        }

        Map<Long, TechProcessOperation> nodeByOperationId =
                new LinkedHashMap<>();

        /*
         * Все рабочие места маршрута загружаются одним запросом,
         * затем распределяются по строкам-операциям.
         */
        Map<Long, WorkPlace> workPlaces = loadWorkPlaces(rows);

        /*
         * Справочник контролируемых параметров — виды и их варианты.
         * Загружается один раз на весь маршрут.
         */
        List<ControlParam> controlParams =
                controlParamRepository.findAllDetailed();

        Map<Long, ControlParam> paramsById = new HashMap<>();
        for (ControlParam cp : controlParams) {
            paramsById.put(cp.getId(), cp);
        }

        /*
         * Ячейки, входящие в изделие: источник значений для параметра
         * «…ячейки…». Загружаются один раз на маршрут и только когда
         * такой параметр в форме действительно есть — иначе форма
         * маршрута без параметров платила бы лишним запросом.
         */
        Map<Long, ProductClassifier> cellOptions = new HashMap<>();

        if (needsCellOptions(controlParams, rows)) {
            for (ProductClassifier cell : classifierRepository
                    .findCellOptionsByOwner(classifier.getId())) {
                cellOptions.put(cell.getId(), cell);
            }
        }

        int position = 0;

        for (TechProcessForm.TechProcessOperationForm row : rows) {
            TechProcessOperation node = new TechProcessOperation();
            node.setOperation(operations.get(row.getOperationId()));
            node.setPosition(position++);
            node.setLaborHours(row.getLaborHours());
            node.setMachineHours(row.getMachineHours());
            node.setDailyLimit(row.getDailyLimit());
            node.setNote(trim(row.getNote()));

            applyWorkPlaces(
                    node,
                    row,
                    workPlaces,
                    operations.get(row.getOperationId())
            );

            applyControlParams(
                    node,
                    row,
                    paramsById,
                    cellOptions
            );

            process.addOperation(node);
            nodeByOperationId.put(row.getOperationId(), node);
        }

        applyDependencies(rows, nodeByOperationId);
        validateAcyclic(new ArrayList<>(nodeByOperationId.values()));
    }

    /*
     * Собирает идентификаторы рабочих мест из всех строк и загружает
     * их одним запросом.
     */
    private Map<Long, WorkPlace> loadWorkPlaces(
            List<TechProcessForm.TechProcessOperationForm> rows
    ) {
        Set<Long> ids = new HashSet<>();

        for (TechProcessForm.TechProcessOperationForm row : rows) {
            if (row.getWorkPlaceIds() != null) {
                ids.addAll(row.getWorkPlaceIds());
            }
        }

        ids.remove(null);

        Map<Long, WorkPlace> loaded = new HashMap<>();

        if (!ids.isEmpty()) {
            workPlaceRepository
                    .findAllById(ids)
                    .forEach(place -> loaded.put(place.getId(), place));

            if (loaded.size() != ids.size()) {
                throw new EntityNotFoundException(
                        "Рабочее место не найдено в справочнике"
                );
            }
        }

        return loaded;
    }

    /*
     * Для каждой операции маршрута обязательна хотя бы одна
     * рабочая площадка: операция может быть распределена
     * между несколькими РМ.
     */
    private void applyWorkPlaces(
            TechProcessOperation node,
            TechProcessForm.TechProcessOperationForm row,
            Map<Long, WorkPlace> workPlaces,
            Operation operation
    ) {
        List<Long> selectedIds = row.getWorkPlaceIds();

        if (selectedIds == null || selectedIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Для операции «"
                            + operation.getOperationName().getName()
                            + "» выберите рабочее место"
            );
        }

        for (Long workPlaceId : selectedIds) {
            if (workPlaceId == null) {
                continue;
            }

            WorkPlace place = workPlaces.get(workPlaceId);

            if (place == null) {
                throw new EntityNotFoundException(
                        "Рабочее место не найдено в справочнике"
                );
            }

            node.getWorkPlaces().add(place);
        }

        if (node.getWorkPlaces().isEmpty()) {
            throw new IllegalArgumentException(
                    "Для операции «"
                            + operation.getOperationName().getName()
                            + "» выберите рабочее место"
            );
        }
    }

    /*
     * Нужен ли маршруту список входящих ячеек.
     *
     * Состав изделия читают только когда в форме есть параметр
     * «…ячейки…»: маршрут без таких параметров не должен платить
     * лишним запросом к классификатору.
     */
    private boolean needsCellOptions(
            List<ControlParam> controlParams,
            List<TechProcessForm.TechProcessOperationForm> rows
    ) {
        Set<Long> cellParamIds = new HashSet<>();

        for (ControlParam param : controlParams) {
            if (param.isCellMode()) {
                cellParamIds.add(param.getId());
            }
        }

        if (cellParamIds.isEmpty()) {
            return false;
        }

        for (TechProcessForm.TechProcessOperationForm row : rows) {
            List<TechProcessForm.ControlParamRow> paramRows =
                    row.getControlParams();

            if (paramRows == null) {
                continue;
            }

            for (TechProcessForm.ControlParamRow paramRow : paramRows) {
                if (paramRow != null
                        && cellParamIds.contains(
                                paramRow.getControlParamId())) {
                    return true;
                }
            }
        }

        return false;
    }

    /*
     * Контролируемые параметры операции.
     *
     * Строка параметра уходит в сохранение, когда выбран сам параметр:
     * маршруту важно, что параметр контролируется и обязателен ли он.
     * Значение зависит от того, как параметр его получает:
     *
     *   ячейка     — параметр, в названии которого есть слово
     *                «ячейка» (ControlParam#isCellMode): значение
     *                выбирают из состава изделия. Режим важнее типа,
     *                поэтому проверяется первым: параметр про ячейку
     *                выбирает ячейку даже если в справочнике его
     *                завели как «поле ввода». Значение необязательно —
     *                у изделия ячеек может и не быть;
     *   TEXT_INPUT — значение вписывают текстом, оно тоже
     *                необязательное: технолог фиксирует сам факт
     *                контроля;
     *   LIST       — второго поля нет: варианты справочника описывают
     *                сам параметр, а конкретное значение вносит
     *                исполнитель при выполнении операции.
     */
    private void applyControlParams(
            TechProcessOperation node,
            TechProcessForm.TechProcessOperationForm row,
            Map<Long, ControlParam> paramsById,
            Map<Long, ProductClassifier> cellOptions
    ) {
        List<TechProcessForm.ControlParamRow> rows =
                row.getControlParams();

        if (rows == null || rows.isEmpty()) {
            return;
        }

        String operationName =
                node.getOperation().getOperationName().getName();

        for (TechProcessForm.ControlParamRow paramRow : rows) {
            if (paramRow == null
                    || paramRow.getControlParamId() == null) {
                continue;
            }

            ControlParam param =
                    paramsById.get(paramRow.getControlParamId());

            if (param == null) {
                throw new EntityNotFoundException(
                        "Контролируемый параметр не найден в справочнике"
                );
            }

            TechProcessOperationControlParam controlParam =
                    new TechProcessOperationControlParam();

            controlParam.setControlParam(param);
            controlParam.setMandatory(paramRow.isMandatory());

            if (param.isCellMode()) {
                applyCellOption(controlParam, paramRow, cellOptions);
            } else if (param.getType() == ControlParamType.TEXT_INPUT) {
                String text = paramRow.getTextValue() == null
                        ? ""
                        : paramRow.getTextValue().trim();

                controlParam.setTextValue(text.isBlank() ? null : text);
            }

            node.addControlParam(controlParam);
        }

        /*
         * Повтор параметра в одной операции.
         *
         * Значение больше не различает строки: у списка его нет
         * вовсе, у ячейки и текста оно необязательное. Поэтому
         * повтором считается один и тот же параметр, выбранный дважды,
         * — индекс uk_tp_operation_control_param на таком нас не
         * поймает (control_param_value_id пуст), и проверка живёт
         * здесь.
         */
        Set<Long> distinctParams = new HashSet<>();

        for (TechProcessOperationControlParam controlParam
                : node.getControlParams()) {
            if (!distinctParams.add(
                    controlParam.getControlParam().getId())) {
                throw new IllegalArgumentException(
                        "Операция «"
                                + operationName
                                + "» содержит повторяющийся "
                                + "контролируемый параметр"
                );
            }
        }
    }

    /*
     * Ячейка — значение «ячеечного» параметра.
     *
     * cellOptions содержит только ячейки, входящие в изделие
     * техпроцесса: отсутствие id в карте значит и «нет такой
     * ячейки», и «ячейка не входит в изделие». Разделять эти случаи
     * незачем — технологу важно только то, что выбрать её нельзя.
     */
    private void applyCellOption(
            TechProcessOperationControlParam controlParam,
            TechProcessForm.ControlParamRow paramRow,
            Map<Long, ProductClassifier> cellOptions
    ) {
        Long cellId = paramRow.getCellClassifierId();

        if (cellId == null) {
            return;
        }

        ProductClassifier cell = cellOptions.get(cellId);

        if (cell == null) {
            throw new IllegalArgumentException(
                    "Ячейка с идентификатором "
                            + cellId
                            + " не входит в изделие техпроцесса"
            );
        }

        controlParam.setCellClassifier(cell);
    }

    /*
     * Зависимости задаются идентификаторами операций-предшественников,
     * поэтому привязка устойчива к добавлению и удалению строк формы.
     */
    private void applyDependencies(
            List<TechProcessForm.TechProcessOperationForm> rows,
            Map<Long, TechProcessOperation> nodeByOperationId
    ) {
        for (TechProcessForm.TechProcessOperationForm row : rows) {
            List<Long> predecessorIds = row.getPredecessorOperationIds();

            if (predecessorIds == null || predecessorIds.isEmpty()) {
                continue;
            }

            TechProcessOperation successor =
                    nodeByOperationId.get(row.getOperationId());

            for (Long predecessorId : predecessorIds) {
                if (predecessorId == null) {
                    continue;
                }

                if (predecessorId.equals(row.getOperationId())) {
                    throw new IllegalArgumentException(
                            "Операция не может зависеть сама от себя"
                    );
                }

                TechProcessOperation predecessor =
                        nodeByOperationId.get(predecessorId);

                if (predecessor == null) {
                    throw new IllegalArgumentException(
                            "Операция-предшественник отсутствует "
                                    + "в списке операций маршрута"
                    );
                }

                successor.getPredecessors().add(predecessor);
            }
        }
    }

    /*
     * Проверка маршрута на отсутствие циклов
     * (алгоритм Кана, топологическая сортировка).
     */
    private void validateAcyclic(List<TechProcessOperation> nodes) {
        int visited = 0;

        Deque<Integer> queue = new ArrayDeque<>();
        int[] indegree = new int[nodes.size()];
        List<List<Integer>> adjacency = buildAdjacency(nodes, indegree);

        for (int i = 0; i < nodes.size(); i++) {
            if (indegree[i] == 0) {
                queue.add(i);
            }
        }

        while (!queue.isEmpty()) {
            int current = queue.poll();
            visited++;

            for (int next : adjacency.get(current)) {
                if (--indegree[next] == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited != nodes.size()) {
            throw new IllegalArgumentException(
                    "Зависимости между операциями образуют цикл"
            );
        }
    }

    /*
     * Индексы узлов соответствуют порядку в списке nodes.
     * Ребро from -> to означает, что to выполняется после from.
     */
    private List<List<Integer>> buildAdjacency(
            List<TechProcessOperation> nodes,
            int[] indegree
    ) {
        Map<TechProcessOperation, Integer> index = new LinkedHashMap<>();

        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }

        List<List<Integer>> adjacency = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i++) {
            adjacency.add(new ArrayList<>());
        }

        for (int i = 0; i < nodes.size(); i++) {
            for (TechProcessOperation predecessor
                    : nodes.get(i).getPredecessors()) {
                Integer from = index.get(predecessor);

                if (from != null) {
                    adjacency.get(from).add(i);
                    indegree[i]++;
                }
            }
        }

        return adjacency;
    }

    /*
     * Уровень узла = длина самого длинного пути от стартовых операций.
     * Нужен, чтобы нарисовать граф послойно.
     */
    private int[] levels(
            List<TechProcessOperation> nodes,
            List<List<Integer>> adjacency
    ) {
        int size = nodes.size();
        int[] indegree = new int[size];
        int[] level = new int[size];

        for (int i = 0; i < size; i++) {
            for (int next : adjacency.get(i)) {
                indegree[next]++;
            }
        }

        Deque<Integer> queue = new ArrayDeque<>();

        for (int i = 0; i < size; i++) {
            if (indegree[i] == 0) {
                queue.add(i);
            }
        }

        int visited = 0;

        while (!queue.isEmpty()) {
            int current = queue.poll();
            visited++;

            for (int next : adjacency.get(current)) {
                level[next] = Math.max(
                        level[next],
                        level[current] + 1
                );

                if (--indegree[next] == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited != size) {
            throw new IllegalStateException(
                    "В маршруте обнаружен замкнутый цикл зависимостей"
            );
        }

        return level;
    }

    private List<TechProcessForm.TechProcessOperationForm> selectedRows(
            TechProcessForm form
    ) {
        if (form.getOperations() == null) {
            return List.of();
        }

        List<TechProcessForm.TechProcessOperationForm> rows =
                new ArrayList<>();

        for (TechProcessForm.TechProcessOperationForm row
                : form.getOperations()) {
            if (row != null && row.getOperationId() != null) {
                rows.add(row);
            }
        }

        return rows;
    }

    private TechProcess save(TechProcess process) {
        try {
            return techProcessRepository.saveAndFlush(process);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "Не удалось сохранить техпроцесс. "
                            + "Проверьте уникальность кода и состав маршрута.",
                    exception
            );
        }
    }

    private String normalizeCode(TechProcessForm form) {
        if (form.getCode() == null || form.getCode().isBlank()) {
            throw new IllegalArgumentException(
                    "Укажите код техпроцесса"
            );
        }

        return form.getCode().trim().toUpperCase();
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }

        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}