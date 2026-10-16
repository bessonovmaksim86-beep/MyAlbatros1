package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.ControlParamForm;
import ru.company.production.service.ControlParamService;

@Controller
@RequestMapping("/technology/controlparam")
@RequiredArgsConstructor
public class ControlParamController {

    private final ControlParamService controlParamService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("activePage", "controlparam");
        model.addAttribute(
                "controlParams",
                controlParamService.findAll()
        );

        return "technology/controlparam/controlparam";
    }

    @GetMapping("/create")
    public String createPage(Model model) {
        if (!model.containsAttribute("controlParamForm")) {
            model.addAttribute(
                    "controlParamForm",
                    new ControlParamForm()
            );
        }

        model.addAttribute("activePage", "controlparam");

        return "technology/controlparam/create";
    }

    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("controlParamForm")
            ControlParamForm controlParamForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                controlParamService.create(controlParamForm);
            } catch (
                    IllegalArgumentException |
                    DataIntegrityViolationException exception
            ) {
                bindingResult.reject(
                        "controlparam.create.error",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("activePage", "controlparam");

            return "technology/controlparam/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Контролируемый параметр успешно создан"
        );

        return "redirect:/technology/controlparam";
    }

    /*
     * Страница изменения: без идентификатора показывает список
     * параметров для выбора, с идентификатором — форму правки.
     */
    @GetMapping("/update")
    public String updatePage(
            @RequestParam(
                    name = "id",
                    required = false
            )
            Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("activePage", "controlparam");
        model.addAttribute(
                "controlParams",
                controlParamService.findAll()
        );

        if (id != null) {
            try {
                model.addAttribute(
                        "selectedParam",
                        controlParamService.get(id)
                );

                model.addAttribute(
                        "controlParamForm",
                        controlParamService.getForm(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/controlparam/update";
            }
        }

        if (!model.containsAttribute("controlParamForm")) {
            model.addAttribute(
                    "controlParamForm",
                    new ControlParamForm()
            );
        }

        return "technology/controlparam/update";
    }

    @PostMapping("/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("controlParamForm")
            ControlParamForm controlParamForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                controlParamService.update(id, controlParamForm);
            } catch (
                    IllegalArgumentException |
                    DataIntegrityViolationException exception
            ) {
                bindingResult.reject(
                        "controlparam.update.error",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("activePage", "controlparam");

            try {
                model.addAttribute(
                        "selectedParam",
                        controlParamService.get(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/controlparam/update";
            }

            model.addAttribute(
                    "controlParams",
                    controlParamService.findAll()
            );

            return "technology/controlparam/update";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Контролируемый параметр успешно изменён"
        );

        return "redirect:/technology/controlparam/update?id=" + id;
    }

    @GetMapping("/delete")
    public String deletePage(
            @RequestParam(
                    name = "id",
                    required = false
            )
            Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("activePage", "controlparam");
        model.addAttribute(
                "controlParams",
                controlParamService.findAll()
        );

        if (id != null) {
            try {
                model.addAttribute(
                        "selectedParam",
                        controlParamService.get(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/controlparam/delete";
            }
        }

        return "technology/controlparam/delete";
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            controlParamService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Контролируемый параметр успешно удалён"
            );
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Параметр используется в операциях техпроцессов "
                            + "и не может быть удалён"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/technology/controlparam";
    }
}
