package com.stockkeeper.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for {@link com.stockkeeper.model.dto.HoldStockRequest}.
 *
 * Cross-field validation that uses the in-memory capacity config to check:
 *   1. capacityType is a supported type (FLIGHT / WAREHOUSE / ULD)
 *   2. classFlag is allowed for that capacity type
 *   3. requestedQuantity > 0
 *   4. All pk/sk-derivation fields required by the capacity type are present
 *
 * This validation runs BEFORE any DynamoDB call, rejecting bad requests early.
 */
@Documented
@Constraint(validatedBy = HoldStockRequestValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidHoldStockRequest {

    String message() default "Invalid hold stock request";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
