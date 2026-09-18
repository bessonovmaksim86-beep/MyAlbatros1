package ru.company.production.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Форма создания заказа на производство.
 *
 * Состав выпуска не редактируется вручную: он выводится из позиций
 * выбранного заказа покупателя — сколько экземпляров заявлено
 * в позиции, столько строк выпуска появится, и каждая получит
 * собственный серийный номер.
 *
 * Поэтому форма держит только привязки (заказ покупателя и
 * комплектовочная операция), сроки и статус.
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductionOrderForm {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private Long id;

    @Size(max = 50, message = "Не более 50 символов")
    private String number;

    /*
     * Обязательность поля проверяется сервисом, а не аннотацией:
     * заказу-комплектующему заказ покупателя не выбирается — он
     * наследуется от родительского выпуска (см. parentProductionOrderId).
     * Аннотация @NotNull отклоняла бы корректную форму ЗНП ещё до
     * обращения к сервису.
     */
    private Long customerOrderId;

    /*
     * Родительский заказ на производство (миграция V21).
     *
     * Пусто → создаётся ЗАКАЗ НА ПРОИЗВОДСТВО (ЗП): в него входят
     * серийники выпускаемых изделий, состав строится из позиций
     * заказа покупателя.
     *
     * Заполнено → создаётся ЗАКАЗ НА ПРОИЗВОДСТВО-КОМПЛЕКТУЮЩИЙ (ЗНП):
     * он входит внутрь конкретного датчика, поэтому серийники ему
     * не выдаются — составом ЗНП являются сами датчики, которые
     * подключаются отдельно.
     */
    private Long parentProductionOrderId;

    @NotNull(message = "Выберите комплектовочную операцию")
    private Long productionOperationId;

    /*
     * Номера датчиков, в которые входит заказ-комплектующий.
     *
     * Серийники НЕ создаются здесь: их выпускает заказ верхнего уровня
     * (ЗП), а комплектующий лишь выбирается из уже выпущенных. Поэтому
     * список заполняется только для ЗНП и только значениями из
     * выбранного родителя — сервис проверяет принадлежность.
     *
     * Поле не размечено @NotEmpty: для заказа верхнего уровня оно
     * обязано оставаться пустым, а обязательность ЗНП проверяется
     * сервисом, где известен уровень заказа.
     */
    private List<Long> serialIds = new ArrayList<>();

    @NotNull(message = "Укажите дату открытия заказа")
    @DateTimeFormat(pattern = "dd.MM.yyyy")
    private LocalDate orderDate;

    @Size(max = 2000, message = "Не более 2000 символов")
    private String note;

    private Long version;

    /*
     * Значения для повторного вывода формы: формат дд.мм.гггг задаётся
     * здесь, потому что диалект Thymeleaf java8time не подключён.
     *
     * Срока исполнения и статуса в форме нет сознательно: срок
     * подтягивается из заказа покупателя, а статус нового выпуска всегда
     * «Новый» и меняется только кнопками в карточке заказа.
     */
    public String getFormattedOrderDate() {
        return formatDate(orderDate);
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : date.format(DATE_FORMATTER);
    }
}
