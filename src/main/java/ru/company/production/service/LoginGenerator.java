package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.company.production.repository.UserRepository;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class LoginGenerator {

    private final UserRepository userRepository;

    public String generateUnique(String fullName) {
        String baseLogin = generateBase(fullName);
        String login = baseLogin;
        int number = 2;

        while (userRepository.existsByUsernameIgnoreCase(login)) {
            login = baseLogin + number;
            number++;
        }

        return login;
    }

    private String generateBase(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }

        String[] parts = fullName.trim().split("\\s+");

        if (parts.length < 2) {
            return "";
        }

        String surname = transliterate(
                parts[0].toLowerCase(Locale.ROOT)
        );

        String name = transliterate(
                parts[1].substring(0, 1).toLowerCase(Locale.ROOT)
        );

        surname = surname.replaceAll("[^a-z0-9]", "");
        name = name.replaceAll("[^a-z0-9]", "");

        if (surname.isBlank() || name.isBlank()) {
            return "";
        }

        String firstLetter =
                name.substring(0, 1).toUpperCase(Locale.ROOT);

        String formattedSurname =
                surname.substring(0, 1).toUpperCase(Locale.ROOT)
                        + surname.substring(1);

        return firstLetter + formattedSurname;
    }

    private String transliterate(String value) {
        StringBuilder result = new StringBuilder();

        for (char symbol : value.toCharArray()) {
            result.append(transliterate(symbol));
        }

        return result.toString();
    }

    private String transliterate(char symbol) {
        return switch (Character.toLowerCase(symbol)) {
            case 'а' -> "a";
            case 'б' -> "b";
            case 'в' -> "v";
            case 'г' -> "g";
            case 'д' -> "d";
            case 'е' -> "e";
            case 'ё' -> "yo";
            case 'ж' -> "zh";
            case 'з' -> "z";
            case 'и' -> "i";
            case 'й' -> "y";
            case 'к' -> "k";
            case 'л' -> "l";
            case 'м' -> "m";
            case 'н' -> "n";
            case 'о' -> "o";
            case 'п' -> "p";
            case 'р' -> "r";
            case 'с' -> "s";
            case 'т' -> "t";
            case 'у' -> "u";
            case 'ф' -> "f";
            case 'х' -> "kh";
            case 'ц' -> "ts";
            case 'ч' -> "ch";
            case 'ш' -> "sh";
            case 'щ' -> "shch";
            case 'ъ', 'ь' -> "";
            case 'ы' -> "y";
            case 'э' -> "e";
            case 'ю' -> "yu";
            case 'я' -> "ya";
            default -> Character.isLetterOrDigit(symbol)
                    ? String.valueOf(Character.toLowerCase(symbol))
                    : "";
        };
    }
}