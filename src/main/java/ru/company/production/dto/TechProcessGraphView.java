package ru.company.production.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модель для визуализации маршрута техпроцесса.
 * Узлы сгруппированы по уровням (колонкам) топологического порядка,
 * каждый уровень рисуется отдельной колонкой графа.
 */
@Getter
@Setter
public class TechProcessGraphView {

    private Long processId;
    private String code;
    private String productDisplayName;
    private String note;

    private List<Node> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();

    /*
     * Рёбра в виде "0-1,1-2" — передаются в разметку,
     * по ним скрипт рисует стрелки.
     */
    private String edgePairs = "";

    private BigDecimal totalLaborHours;
    private BigDecimal totalMachineHours;

    /*
     * Длительность маршрута по критическому пути:
     * в часах и в рабочих днях (с учётом продолжительности
     * рабочего дня).
     */
    private BigDecimal criticalPathHours = BigDecimal.ZERO;
    private BigDecimal criticalPathDays = BigDecimal.ZERO;
    private int workdayHours = 8;

    /*
     * Узлы по уровням топологического порядка:
     * каждый уровень рисуется отдельной колонкой графа.
     */
    private Map<Integer, List<Node>> levels = new LinkedHashMap<>();

    public void addNode(int level, Node node) {
        levels.computeIfAbsent(level, key -> new ArrayList<>())
                .add(node);
    }

    @Getter
    @Setter
    public static class Node {
        private Long id;
        private int level;
        private int position;
        private String name;
        private String typeName;

        /*
         * Код типа операции (PRODUCTION, TRANSFER и т.д.) —
         * используется для выбора цвета узла.
         */
        private String typeCode;

        /*
         * Форма и цвет узла зависят от типа операции:
         * прямоугольник, ромб, параллелограмм и т.д.
         */
        private String shape = "rect";

        /*
         * Длительность операции на участке: часы и рабочие дни.
         */
        private BigDecimal durationHours = BigDecimal.ZERO;
        private BigDecimal durationDays = BigDecimal.ZERO;

        private String serviceName;
        private String organizationUnitName;
        private String executorRoleName;
        private BigDecimal laborHours;
        private BigDecimal machineHours;
        private Integer dailyLimit;
        private String note;
        private List<String> workPlaces = new ArrayList<>();
        private List<String> dependsOn = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Edge {
        private Long from;
        private Long to;

        public Edge() {
        }

        public Edge(Long from, Long to) {
            this.from = from;
            this.to = to;
        }
    }
}
