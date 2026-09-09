package ru.company.production.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class PasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "123456789";
    private static final String SPECIAL = "!@#$%_-";
    private static final String ALL =
            UPPER + LOWER + DIGITS + SPECIAL;

    public String generate() {
        List<Character> characters = new ArrayList<>();

        characters.add(randomCharacter(UPPER));
        characters.add(randomCharacter(LOWER));
        characters.add(randomCharacter(DIGITS));
        characters.add(randomCharacter(SPECIAL));

        while (characters.size() < 14) {
            characters.add(randomCharacter(ALL));
        }

        Collections.shuffle(characters, RANDOM);

        StringBuilder password = new StringBuilder();

        for (Character character : characters) {
            password.append(character);
        }

        return password.toString();
    }

    private char randomCharacter(String source) {
        return source.charAt(RANDOM.nextInt(source.length()));
    }
}