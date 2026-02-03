package com.stockkeeper.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for state-transition request DTOs
 * (commit, load, release).
 *
 * Validates:
 *   1. capacityType is supported
 *   2. All pk/sk-derivation fields required by the capacity type are present
 *
 * The actual state-transition legality (e.g. HELD → COMMITTED) is checked
 * in the service layer after reading the current reservation state from DynamoDB,
 * because we need the current state to validate the transition.
 */
@Documented
@Constraint(validatedBy = TransitionRequestValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTransitionRequest {

    String message() default "Invalid stock transition request";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
