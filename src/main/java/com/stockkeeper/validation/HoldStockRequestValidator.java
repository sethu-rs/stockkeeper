package com.stockkeeper.validation;

import com.stockkeeper.config.CapacityConfigLoader;
import com.stockkeeper.model.dto.HoldStockRequest;
import com.stockkeeper.util.StockKeyGenerator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link HoldStockRequest} using the in-memory capacity config.
 *
 * This is a Spring-managed bean — the {@link CapacityConfigLoader} is injected
 * by Spring's {@code SpringConstraintValidatorFactory}, which means we can
 * access the config that was loaded at startup.
 *
 * Validation rules (all checked before DynamoDB is touched):
 *   1. requestedQuantity must be > 0
 *   2. capacityType must be one of the supported types in capacity-config.yml
 *   3. If classFlag is provided, it must be in the allowedClassFlags for that type
 *   4. All fields required to derive the stock pk/sk must be present
 */
@RequiredArgsConstructor
public class HoldStockRequestValidator
        implements ConstraintValidator<ValidHoldStockRequest, HoldStockRequest> {

    private final CapacityConfigLoader configLoader;

    @Override
    public boolean isValid(HoldStockRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true; // null handling is @NotNull's job
        }

        List<String> errors = new ArrayList<>();

        // Rule 1: Quantity must be positive
        if (request.requestedQuantity() <= 0) {
            errors.add("requestedQuantity must be greater than 0");
        }

        // Rule 2: Capacity type must be supported
        if (request.capacityType() == null || request.capacityType().isBlank()) {
            errors.add("capacityType is required");
        } else if (!configLoader.isSupportedCapacityType(request.capacityType())) {
            errors.add("Unsupported capacity type: " + request.capacityType()
                    + ". Supported types: " + configLoader.getCapacityConfig().getCapacityTypes().keySet());
        } else {
            // Rule 3: Class flag must be valid for the capacity type (if provided)
            if (request.classFlag() != null && !request.classFlag().isBlank()) {
                if (!configLoader.isClassFlagAllowed(request.capacityType(), request.classFlag())) {
                    errors.add("Class flag '" + request.classFlag()
                            + "' is not allowed for capacity type " + request.capacityType()
                            + ". Allowed flags: "
                            + configLoader.getTypeConfig(request.capacityType()).getAllowedClassFlags());
                }
            }

            // Rule 4: Required key-derivation fields must be present
            errors.addAll(StockKeyGenerator.validateKeyFields(request));
        }

        if (errors.isEmpty()) {
            return true;
        }

        // Replace the default constraint message with specific error messages
        context.disableDefaultConstraintViolation();
        for (String error : errors) {
            context.buildConstraintViolationWithTemplate(error)
                    .addConstraintViolation();
        }
        return false;
    }
}
