package ru.company.production.dto;

import ru.company.production.entity.ProductType;

public record ClassifierOption(
        Long id,
        Integer code,
        ProductType productType,
        String displayName
) {
}