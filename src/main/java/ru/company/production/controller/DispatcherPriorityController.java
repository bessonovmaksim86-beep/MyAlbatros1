package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.OrderPriorityForm;
import ru.company.production.entity.OrderPriority;
import ru.company.production.service.OrderPriorityService;

/**
 * Диспетчерский отдел: справочник приоритетов планирования.
 *
 * Приоритет задаёт правила запуска заказа в работу — запуск,
 * допустимую загруженность участка, межоперационные переходы и
 * максимальную дату изготовления. Эти же правила проверяются при
 * планировании заказа покупателя, поэтому справочник относится
 * к диспетчерскому отделу, а не к техподготовке.
 */
@Controller
@RequestMapping("/dispatcher/priority")
@RequiredArgsConstructor
public class DispatcherPriorityController {

    private final OrderPriorityService orderPriorityService;

    /**
     * Список приоритетов и форма занесения нового.
     */
    @GetMapping({"", "/"})
    public String index(
            @ModelAttribute("orderPriorityForm")
            OrderPriorityForm orderPriorityForm,
            Model model
    ) {
        fillIndexModel(model);

        return "dispatcher/priority/index";
    }

    /**
     * Занесение нового приоритета.
     *
     * Код (ПР-NNN) не запрашивается: он машинный, правила описываются
     * четырьмя параметрами и наименованием.
     */
    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("orderPriorityForm")
            OrderPriorityForm orderPriorityForm,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!errors.hasErrors()) {
            try {
                orderPriorityService.create(orderPriorityForm);
            } catch (
                    IllegalArgumentException
                    | EntityNotFoundException exception
            ) {
                errors.reject("priority.save", exception.getMessage());
            }
        }

        if (errors.hasErrors()) {
            fillIndexModel(model);

            return "dispatcher/priority/index";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Приоритет «" + orderPriorityForm.getName().trim()
                        + "» успешно добавлен"
        );

        return "redirect:/dispatcher/priority";
    }

    /**
     * Форма правки приоритета со списком для перехода между правилами.
     */
    @GetMapping("/{id}/edit")
    public String editPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        var priority = getOrder(id, redirectAttributes);

        if (priority == null) {
            return "redirect:/dispatcher/priority";
        }

        model.addAttribute(
                "orderPriorityForm",
                orderPriorityService.getForm(id)
        );
        model.addAttribute("editing", true);
        model.addAttribute("priority", priority);

        fillEditModel(model, id);

        return "dispatcher/priority/edit";
    }

    /**
     * Приоритет для страницы правки: ошибка возвращается флеш-сообщением,
     * а не исключением, — так же, как в остальных разделах отдела.
     */
    private OrderPriority getOrder(
            Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return orderPriorityService.get(id);
        } catch (
                IllegalArgumentException
                | EntityNotFoundException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return null;
        }
    }

    /**
     * Сохранение изменений приоритета.
     */
    @PostMapping("/{id}/edit")
    public String edit(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("orderPriorityForm")
            OrderPriorityForm orderPriorityForm,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!errors.hasErrors()) {
            try {
                orderPriorityService.update(id, orderPriorityForm);
            } catch (
                    IllegalArgumentException
                    | EntityNotFoundException exception
            ) {
                errors.reject("priority.save", exception.getMessage());
            }
        }

        if (errors.hasErrors()) {
            OrderPriority priority = getOrder(id, redirectAttributes);

            if (priority == null) {
                return "redirect:/dispatcher/priority";
            }

            model.addAttribute("editing", true);
            model.addAttribute("priority", priority);

            fillEditModel(model, id);

            return "dispatcher/priority/edit";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Приоритет «" + orderPriorityForm.getName().trim()
                        + "» успешно изменён"
        );

        return "redirect:/dispatcher/priority/" + id + "/edit";
    }

    /**
     * Вывод приоритета из работы (архив).
     *
     * Физического удаления нет: на приоритет ссылаются заказы покупателя,
     * поэтому правило только помечается недействующим.
     */
    @PostMapping("/{id}/deactivate")
    public String deactivate(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        return changeActivity(
                id,
                redirectAttributes,
                true
        );
    }

    /**
     * Возврат приоритета в работу.
     */
    @PostMapping("/{id}/activate")
    public String activate(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        return changeActivity(
                id,
                redirectAttributes,
                false
        );
    }

    private String changeActivity(
            Long id,
            RedirectAttributes redirectAttributes,
            boolean toArchive
    ) {
        try {
            if (toArchive) {
                orderPriorityService.deactivate(id);
            } else {
                orderPriorityService.activate(id);
            }
        } catch (
                IllegalArgumentException
                | EntityNotFoundException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/priority";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                toArchive
                        ? "Приоритет выведен из работы — он больше "
                        + "не предлагается в заказах"
                        : "Приоритет возвращён в работу"
        );

        return "redirect:/dispatcher/priority";
    }

    private void fillIndexModel(Model model) {
        model.addAttribute("activePage", "priority");

        model.addAttribute(
                "priorities",
                orderPriorityService.findAll()
        );

        model.addAttribute(
                "priorityCount",
                orderPriorityService.countActive()
        );
    }

    /**
     * Справочник для страницы правки: список нужен, чтобы переключаться
     * между приоритетами, не возвращаясь в общий список.
     */
    private void fillEditModel(Model model, Long editingId) {
        fillIndexModel(model);

        model.addAttribute("editingId", String.valueOf(editingId));
    }
}