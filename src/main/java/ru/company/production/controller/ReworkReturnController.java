package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ReworkReturnService;

import java.util.List;

/**
 * Возврат на доработку — раздел начальника цеха.
 *
 * Здесь начальник цеха видит изделия, возвращённые с контроля БТК,
 * выбирает операции своей переделки и решает: переделывать в своём
 * цехе или отправить изделие назад по маршруту — в предшествующий цех.
 *
 * Пункт меню открыт только руководителям подразделений (SDP_*_MASTER
 * и остальные MASTER-роли).
 */
@Controller
@RequestMapping("/workshop/rework-return")
@RequiredArgsConstructor
public class ReworkReturnController {

    private final ReworkReturnService reworkReturnService;
    private final UserRepository userRepository;

    /**
     * Список изделий на доработке.
     *
     * Карточки строятся по экземплярам: БТК возвращает изделие целиком,
     * а цеху показывают то, что вернули. В карточку попадают все строки
     * маршрута, принадлежащие подразделениям цеха.
     */
    @GetMapping({"", "/"})
    public String index(Authentication authentication, Model model) {
        AppUser supervisor = currentUser(authentication);

        var cards = reworkReturnService.findOpenReturns(supervisor);

        model.addAttribute("cards", cards);
        model.addAttribute("returnCount", cards.size());
        model.addAttribute("activePage", "rework-return");

        return "workshop/rework-return/index";
    }

    /**
     * Переделать отмеченные операции — возврат на доработку в текущий цех.
     *
     * Проведение снимается только с отмеченных строк. Каскадом — с
     * проведённых строк, идущих в маршруте после отмеченных: переделанная
     * работа не может оставаться под закрытой, иначе следующая по
     * маршруту осталась бы разблокированной.
     *
     * На каждую снятую строку заводится {@link ReworkReturn}: исполнитель
     * видит причину по своей работе, а возврат остаётся в истории.
     */
    @PostMapping("/mark-for-rework")
    public String markForRework(
            @RequestParam List<Long> rowIds,
            @RequestParam String reason,
            @RequestParam(defaultValue = "false") boolean cascade,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser supervisor = currentUser(authentication);

        try {
            String msg = reworkReturnService.markForRework(
                    rowIds, reason, cascade, supervisor);

            redirectAttributes.addFlashAttribute("successMessage", msg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/workshop/rework-return";
    }

    /**
     * Отправить изделие на доработку в предшествующий цех.
     *
     * Логика как у возврата БТК: проведение снимается со всех строк
     * экземпляра, и изделие уходит назад по маршруту.
     */
    @PostMapping("/send-to-predecessor")
    public String sendToPredecessor(
            @RequestParam List<Long> rowIds,
            @RequestParam String reason,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser supervisor = currentUser(authentication);

        try {
            String msg = reworkReturnService.sendToPredecessor(
                    rowIds, reason, supervisor);

            redirectAttributes.addFlashAttribute("successMessage", msg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/workshop/rework-return";
    }

    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись руководителя не найдена"
                ));
    }
}
