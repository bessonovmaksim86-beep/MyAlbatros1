package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.TechProcessForm;
import ru.company.production.dto.TechProcessGraphView;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.WorkPlace;
import ru.company.production.repository.ClassifierOptionProjection;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.ProductClassifierRepository;
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
    private final ProductClassifierRepository classifierRepository;
    private final OperationRepository operationRepository;
    private final WorkPlaceRepository workPlaceRepository;
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
            Long targetClassifierId
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

        boolean alreadyApplied = process.getAppliedClassifiers()
                .stream()
                .anyMatch(item -> item.getId().equals(target.getId()));

        if (alreadyApplied) {
            throw new IllegalArgumentException(
                    "Техпроцесс уже применён к выбранному изделию"
            );
        }

        process.getAppliedClassifiers().add(target);

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
     * Возвращает изделия, к которым применён техпроцесс
     * (для отображения в списке и на странице применения).
     */
    @Transactional(readOnly = true)
    public List<ClassifierOptionProjection> findAppliedOptions(Long id) {
        TechProcess process = techProcessRepository
                .findWithAppliedClassifiers(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Техпроцесс с идентификатором "
                                + id
                                + " не найден"
                ));

        Set<Long> appliedIds = new HashSet<>();

        process.getAppliedClassifiers()
                .forEach(item -> appliedIds.add(item.getId()));

        return classifierRepository.findAllOptions().stream()
                .filter(option -> appliedIds.contains(option.getId()))
                .toList();
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
     * комплектовочная — пятиугольник.
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
            default -> "rect";
        };
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
        boolean mainApplied = process.getAppliedClassifiers()
                .stream()
                .anyMatch(item -> item.getId().equals(classifier.getId()));

        if (!mainApplied) {
            process.getAppliedClassifiers().add(classifier);
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