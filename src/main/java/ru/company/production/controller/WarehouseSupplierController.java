package ru.company.production.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.SupplierForm;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WarehouseSupplierService;

/**
 * Поставщики склада: ведение имён в справочник и сводка по ним.
 *
 * Страница одна и совмещает форму с таблицей: поставщиков на складе
 * десятки, и отдельная карточка каждого была бы лишним переходом,
 * тогда как оператору нужен общий список с объёмами приходов.
 *
 * После сохранения — redirect: повторный F5 не должен заводить
 * дубль написания, а форма прихода в другом окне не должна видеть
 * устаревший список подсказок.
 */
@Controller
@RequestMapping("/warehouse/suppliers")
@RequiredArgsConstructor
public class WarehouseSupplierController {

    private final WarehouseSupplierService supplierService;
    private final UserRepository userRepository;

    @GetMapping({"", "/"})
    public String index(
            @ModelAttribute("supplierForm") SupplierForm supplierForm,
            Model model
    ) {
        fillModel(model);

        return "warehouse/suppliers/index";
    }

    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("supplierForm") SupplierForm supplierForm,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        if (!errors.hasErrors()) {
            try {
                supplierService.create(supplierForm, currentUser(authentication));

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Поставщик «" + supplierForm.getName().trim()
                                + "» добавлен в справочник"
                );
                redirectAttributes.addFlashAttribute(
                        "supplierForm",
                        new SupplierForm()
                );

                return "redirect:/warehouse/suppliers";
            } catch (
                    IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject("supplier.create", exception.getMessage());
            }
        }

        fillModel(model);

        return "warehouse/suppliers/index";
    }

    private void fillModel(Model model) {
        model.addAttribute("activePage", "suppliers");
        model.addAttribute("suppliers", supplierService.findStats());
    }

    /*
     * Автор записи нужен для истории справочника: одно и то же
     * написание позже сверяют с тем, кто его ввёл.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));
    }
}
