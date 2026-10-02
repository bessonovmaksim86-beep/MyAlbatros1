package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.service.CustomerOrderSuspensionService;

/**
 * Диспетчерский отдел: приостановка и возобновление заказа покупателя.
 *
 * Контроллер выделен отдельно от {@code DispatcherController} по той же
 * причине, что {@code DispatcherCustomerOrderDeleteController}: раздел
 * диспетчера разросся до нескольких тысяч строк, и держать в одном
 * классе ещё и остановку работы означало бы искать кнопку по всему
 * модулю.
 *
 * Общий префикс {@code /dispatcher/customer-order} совпадает с путями
 * основного контроллера — Spring сопоставляет обработчики по полному
 * пути, а не по классу, поэтому адреса привычны для ссылок в шаблонах
 * и ни с одним из существующих под-маршрутов не спорят
 * ({@code /{id}/suspend} и {@code /{id}/resume} в
 * {@code DispatcherController} отсутствуют).
 *
 * Приостановка отличается от удаления: заказ НЕ снимается с работы, а
 * остаётся во всех списках, но «замораживается» — до возобновления по
 * нему нельзя проводить и назначать цеховые работы (см.
 * {@link CustomerOrderSuspensionService#assertOrderOperable}).
 */
@Controller
@RequestMapping("/dispatcher/customer-order")
@RequiredArgsConstructor
public class DispatcherCustomerOrderSuspensionController {

    private final CustomerOrderSuspensionService suspensionService;

    /**
     * Приостановка заказа. Причина необязательна, но сохраняется:
     * через месяц она объясняет, почему заказ стоял.
     *
     * Успех и отказ возвращают в карточку заказа — там же, где кнопка,
     * и там же виден баннер «заказ приостановлен».
     */
    @PostMapping("/{id}/suspend")
    public String suspend(
            @PathVariable Long id,
            @RequestParam(name = "reason", required = false) String reason,
            RedirectAttributes redirectAttributes
    ) {
        CustomerOrder order;

        try {
            order = suspensionService.suspend(id, reason);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/customer-order/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ покупателя «" + order.getNumber()
                        + "» приостановлен: операции по нему недоступны "
                        + "до возобновления"
        );

        return "redirect:/dispatcher/customer-order/" + id;
    }

    /**
     * Возобновление заказа: он возвращается в статус, в котором стоял
     * до остановки, и снова становится оперуемым.
     */
    @PostMapping("/{id}/resume")
    public String resume(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        CustomerOrder order;

        try {
            order = suspensionService.resume(id);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/customer-order/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ покупателя «" + order.getNumber()
                        + "» возобновлён, операции снова доступны"
        );

        return "redirect:/dispatcher/customer-order/" + id;
    }
}
