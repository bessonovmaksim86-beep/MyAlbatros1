package ru.company.production.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/admin")
    public String adminPage() {
        return "admin/index";
    }

    @GetMapping("/quality")
    public String qualityPage() {
        return "quality";
    }

    @GetMapping("/production")
    public String productionPage() {
        return "production";
    }

    @GetMapping("/login")
    public String login(HttpServletRequest request) {
        Object csrfAttribute =
                request.getAttribute(CsrfToken.class.getName());

        if (csrfAttribute instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }

        return "login";
    }
}