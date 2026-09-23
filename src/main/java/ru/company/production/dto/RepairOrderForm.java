package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Форма заказа на ремонт (ЗНП).
 *
 * Ячейки приходят строками «id группы|полный номер»: пара нужна целиком,
 * потому что один и тот же номер мог быть забракован в разных группах с
 * разными причинами, а заказ обязан ссылаться на конкретную группу.
 *
 * Поставщик выбирается из справочника склада, но может быть вписан и
 * текстом — написание в справочнике не обязательное условие ремонта.
 */
@Getter
@Setter
@NoArgsConstructor
public class RepairOrderForm {

    /** Ячейки: «groupId|121200023». */
    private List<String> cells = new ArrayList<>();

    /** Идентификатор поставщика из справочника склада. */
    private Long supplierId;

    /** Написание поставщика, выбранное из справочника. */
    private String supplierChoice;

    @Size(max = 255, message = "Наименование поставщика не длиннее 255 знаков")
    private String supplierName;

    /** Вид заказа: ремонт ячеек, подсборок или выдача комплектации. */
    @NotBlank(message = "Выберите вид заказа — ремонт ячеек, "
            + "ремонт подсборок или выдачу комплектации")
    private String orderType;

    @Size(max = 2000, message = "Примечание не длиннее 2000 знаков")
    private String note;

    /**
     * Подсказка формы: «другое» написание поставщика вписывают вручную.
     *
     * Отдельного чекбокса нет — признак выводится из выбора в списке,
     * чтобы форма не предлагала противоречивые состояния.
     */
    public boolean isCustomSupplier() {
        return "CUSTOM".equalsIgnoreCase(
                supplierChoice == null ? "" : supplierChoice.trim()
        );
    }
}
