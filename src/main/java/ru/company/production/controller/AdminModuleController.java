package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.company.production.service.AdminConsoleService;

/**
 * Главная страница администратора и раздел «Роли и вход».
 *
 * Раздел «Пользователи» живёт в {@code AdminUserController}; здесь собраны
 * сводка по базе и переходы в интерфейсы других ролей.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminModuleController {

    private final AdminConsoleService consoleService;

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/admin/general";
    }

    @GetMapping("/general")
    public String general(Model model) {
        model.addAttribute("activePage", "general");

        model.addAttribute("userCount", consoleService.userCount());
        model.addAttribute("activeUserCount", consoleService.activeUserCount());
        model.addAttribute("administratorCount", consoleService.administratorCount());
        model.addAttribute("roleCount", consoleService.roleCount());

        model.addAttribute("techProcessCount", consoleService.techProcessCount());
        model.addAttribute("operationCount", consoleService.operationCount());
        model.addAttribute("classifierCount", consoleService.classifierCount());
        model.addAttribute("workPlaceCount", consoleService.workPlaceCount());

        model.addAttribute(
                "customerOrderCount",
                consoleService.customerOrderCount()
        );
        model.addAttribute(
                "productionOrderCount",
                consoleService.productionOrderCount()
        );
        model.addAttribute("customerCount", consoleService.customerCount());
        model.addAttribute("priorityCount", consoleService.priorityCount());
        model.addAttribute(
                "staffBindingCount",
                consoleService.staffBindingCount()
        );

        return "admin/general";
    }

    @GetMapping("/roles")
    public String roles(Model model) {
        model.addAttribute("activePage", "roles");

        model.addAttribute(
                "roleAccesses",
                consoleService.roleAccesses()
        );

        return "admin/roles";
    }
}
