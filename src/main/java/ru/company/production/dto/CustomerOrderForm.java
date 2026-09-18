package ru.company.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import ru.company.production.entity.CustomerOrderStatus;
import ru.company.production.entity.CustomerOrderType;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.TechProcessType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Форма создания заказа покупателя.
 *
 * Изделий (позиций) в заказе может быть любое количество —
 * список items не ограничен по размеру, но требует минимум
 * одну заполненную строку.
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerOrderForm {

    /** Поле ссылки может остаться пустым — это допустимо. */
    private static final String BLANK_OR_URL =
            "^\\s*$|^https?://.+$";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private Long id;

    @NotBlank(message = "Укажите номер заказа")
    @Size(max = 50, message = "Не более 50 символов")
    private String number;

    @NotNull(message = "Выберите покупателя")
    private Long customerId;

    @NotNull(message = "Укажите дату заказа")
    @DateTimeFormat(pattern = "dd.MM.yyyy")
    private LocalDate orderDate;

    @NotNull(message = "Укажите срок исполнения")
    @DateTimeFormat(pattern = "dd.MM.yyyy")
    private LocalDate dueDate;

    @Size(max = 100, message = "Не более 100 символов")
    private String contractNumber;

    @Size(max = 2000, message = "Не более 2000 символов")
    private String note;

    /*
     * Ссылки на Б24 проверяются только по префиксу: полный разбор URL
     * отклонял бы внутренние адреса вида http://b24.local/...,
     * которые в системе и используются.
     */
    @Pattern(
            regexp = BLANK_OR_URL,
            message = "Укажите ссылку, начинающуюся с http:// или https://"
    )
    @Size(max = 500, message = "Не более 500 символов")
    private String b24OrderUrl;

    @Pattern(
            regexp = BLANK_OR_URL,
            message = "Укажите ссылку, начинающуюся с http:// или https://"
    )
    @Size(max = 500, message = "Не более 500 символов")
    private String b24ChecklistUrl;

    /**
     * Приоритет планирования — идентификатор справочника.
     * Выбор обязан быть: заказ без приоритета нельзя поставить
     * в очерёдность, а правила запуска читаются из справочника.
     */
    @NotNull(message = "Выберите приоритет")
    private Long priorityId;

    private CustomerOrderStatus status;

    /**
     * Вид заказа (производство/ремонт/внутренний).
     * Пустое значение трактуется как «Производство».
     */
    private CustomerOrderType orderType;

    @Valid
    @NotEmpty(message = "Добавьте хотя бы одно изделие в заказ")
    private List<CustomerOrderItemForm> items = new ArrayList<>();

    private Long version;

    /*
     * Значения полей даты для повторного вывода формы:
     * формат дд.мм.гггг задаётся здесь, потому что диалект
     * Thymeleaf java8time в проект не подключён.
     */
    public String getFormattedOrderDate() {
        return formatDate(orderDate);
    }

    public String getFormattedDueDate() {
        return formatDate(dueDate);
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : date.format(DATE_FORMATTER);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class CustomerOrderItemForm {

        /*
         * Изделие выбирается из списка для приборов, ячеек и систем.
         * Для датчика изделие не выбирают: диспетчер вносит полное
         * обозначение вручную (fullName), а классификатор подбирается
         * автоматически. Поэтому поле без @NotNull — обязательность
         * проверяет сервис: либо идентификатор, либо fullName датчика.
         */
        private Long productClassifierId;

        /**
         * Тип изделия строки (прибор/датчик/ячейка/система).
         * Приходит из селектора типа: определяет, откуда брать
         * изделие — из списка или из ручного ввода.
         */
        private ProductType productType;

        /**
         * Полное обозначение датчика «как в КД», вводимое вручную:
         * «Датчик уровня ультразвуковой ДУУ2М-12-1-15,00-0,15-ОМ1,5**-3
         * (поплавок титановый тип II УНКР.305446.080)».
         */
        @Size(max = 500, message = "Полное наименование: не более 500 символов")
        private String fullName;

        @NotNull(message = "Укажите количество")
        @DecimalMin(
                value = "0.001",
                message = "Количество должно быть больше нуля"
        )
        @Digits(
                integer = 12,
                fraction = 3,
                message = "Количество: не более 12 цифр и 3 после запятой"
        )
        private BigDecimal quantity;

        @Size(max = 20, message = "Недопустимая единица измерения")
        private String unit;

        /**
         * Тип техпроцесса для производства.
         * Пустое значение трактуется как «Базовое» — текущий техпроцесс,
         * привязанный к изделию позиции.
         */
        private TechProcessType techProcessType;

        @DateTimeFormat(pattern = "dd.MM.yyyy")
        private LocalDate dueDate;

        @Size(max = 2000, message = "Не более 2000 символов")
        private String note;

        /*
         * Значения для повторного вывода формы: диалект Thymeleaf
         * java8time в проект не подключён, поэтому количество и дата
         * форматируются здесь.
         */
        public String getFormattedQuantity() {
            return quantity == null
                    ? ""
                    : quantity.stripTrailingZeros().toPlainString();
        }

        public String getFormattedDueDate() {
            return dueDate == null
                    ? ""
                    : dueDate.format(DATE_FORMATTER);
        }
    }
}
