package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.company.production.dto.PassportView;
import ru.company.production.service.ProductPassportService;

/**
 * Паспорт изделия — хронология ячейки или подсборки по её номеру.
 *
 * Отдельный контроллер, а не раздел: паспорт надмодульный, как и сводка
 * поиска. Номера движутся между БВК, изолятором, ремонтом, складом и
 * производством, и открывать историю по ссылке из поиска в чужом разделе
 * значило бы требовать у пользователя прав того раздела. Доступ — любой
 * аутентифицированный (общее правило anyRequest().authenticated()):
 * показывать уже собранную историю безопаснее, чем забрасывать
 * пользователя в раздел, где он не работает.
 *
 * Номер в пути — строка, а не идентификатор: паспорт существует и для
 * номеров, в реестр ещё не внесённых, у которых строки реестра просто
 * нет.
 */
@Controller
@RequiredArgsConstructor
public class ProductPassportController {

    private final ProductPassportService passportService;

    @GetMapping("/passport/{fullNumber}")
    public String passport(@PathVariable String fullNumber, Model model) {

        PassportView passport = passportService.build(fullNumber);

        model.addAttribute("passport", passport);
        model.addAttribute("requestedNumber", fullNumber);

        return "search/passport";
    }
}
