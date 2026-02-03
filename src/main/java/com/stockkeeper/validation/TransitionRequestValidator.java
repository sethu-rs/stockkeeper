package com.stockkeeper.validation;

import com.stockkeeper.config.CapacityConfigLoader;
import com.stockkeeper.model.dto.StockKeySource;
import com.stockkeeper.util.StockKeyGenerator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates any {@link StockKeySource} (commit, load, release requests).
 *
 * Checks:
 *   1. capacityType is one of the supported types
 *   2. All fields required to derive the stock pk/sk are present
 *
 * The validator operates on the {@link StockKeySource} interface, so a single
 * validator class works for CommitStockRequest, LoadStockRequest, and
 * ReleaseStockRequest — all of which implement StockKeySource.
 */
@RequiredArgsConstructor
public class TransitionRequestValidator
        implements ConstraintValidator<ValidTransitionRequest, StockKeySource> {

    private final CapacityConfigLoader configLoader;

    @Override
    public boolean isValid(StockKeySource source, ConstraintValidatorContext context) {
        if (source == null) {
            return true;
        }

        List<String> errors = new ArrayList<>();

        // Rule 1: Capacity type must be supported
        if (source.capacityType() == null || source.capacityType().isBlank()) {
            errors.add("capacityType is required");
        } else if (!configLoader.isSupportedCapacityType(source.capacityType())) {
            errors.add("Unsupported capacity type: " + source.capacityType()
                    + ". Supported types: " + configLoader.getCapacityConfig().getCapacityTypes().keySet());
        } else {
            // Rule 2: Required key-derivation fields
            errors.addAll(StockKeyGenerator.validateKeyFields(source));
        }

        if (errors.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        for (String error : errors) {
            context.buildConstraintViolationWithTemplate(error)
                    .addConstraintViolation();
        }
        return false;
    }
}
