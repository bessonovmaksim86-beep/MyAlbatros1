package ru.company.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Данные карточки входного контроля оприходованной партии.
 *
 * Номера ячеек передаются текстом, а не списком идентификаторов: их
 * вводят с этикетки или сканера по одному, и поле остаётся полем
 * ввода, а не таблицей с чекбоксами. Разбор и сверка с партией —
 * задача сервиса, которому известен диапазон номеров партии.
 *
 * Выборочный контроль по определению неполный: проверенных ячеек
 * может быть меньше, чем в партии. Поэтому перечень необязателен как
 * полный, но каждый внесённый номер обязан принадлежать партии.
 *
 * Брак передаётся группами: ячейки с одним дефектом и одним
 * дальнейшим исходом объединяются, поэтому партию можно часть
 * вернуть поставщику, а часть оставить в изоляторе брака.
 */
@Getter
@Setter
@NoArgsConstructor
public class InputControlForm {

    /** Идентификатор партии: из него берутся её номера. */
    @NotBlank(message = "Партия не выбрана")
    private String batchId;

    /** Номера выборочного контроля: по одному в строке. */
    @Size(max = 20000, message = "Список выборочного контроля слишком длинный")
    private String sampleCellsText;

    /** Выборочный контроль по всей партии. */
    private boolean sampleAll;

    /*
     * Вид входного контроля: визуальный либо визуальный и технический.
     *
     * Выбор обязателен — по нему ячейки партии отмечают прошедшими
     * контроль, поэтому в сервисе он проверяется вместе с остальными
     * условиями закрытия карточки, а не аннотацией: сообщение об
     * ошибке должно быть человеческим («не выбран вид контроля»), а не
     * служебным.
     */
    private String inputControlType;

    /*
     * Группы брака в порядке заполнения. Пустой список — брак не
     * выявлен; блок группы, оставленный пустым, сервис отбрасывает,
     * поэтому кнопка «добавить» без заполнения форму не ломает.
     */
    @Valid
    private List<DefectGroupForm> defectGroups = new ArrayList<>();

    @Size(max = 2000, message = "Примечание: не более 2000 символов")
    private String note;

    /** Партия в форме числом — идентификатор приходит строкой из формы. */
    public Long batchIdAsLong() {
        try {
            return batchId == null || batchId.isBlank()
                    ? null
                    : Long.valueOf(batchId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Одна группа брака: перечень ячеек, причина и решение.
     *
     * Ступени причин и текст «Другого» относятся к своей группе:
     * иначе две группы с разными дефектами делили бы одну причину.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class DefectGroupForm {

        /** Забракованные номера группы: по одному в строке. */
        @Size(max = 20000, message = "Список забракованных ячеек слишком длинный")
        private String defectCellsText;

        /** Брак признан по всей партии. */
        private boolean defectAll;

        /*
         * Цепочка выбранных причин: корневая причина и её уточнения, в
         * порядке выбора. Список, а не одно значение, — дерево причин
         * подразумевает, что в группе остаются и группа, и уточнение.
         */
        private List<Long> reasonIds = new ArrayList<>();

        /* Текст, введённый в ступени «Другое»; пополняет справочник. */
        @Size(max = 500, message = "Причина: не более 500 символов")
        private String customReasonText;

        /** Решение по ячейкам группы. */
        private String decision;
    }
}