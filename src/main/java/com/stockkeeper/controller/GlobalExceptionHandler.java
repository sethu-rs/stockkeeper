package com.stockkeeper.controller;

import com.stockkeeper.exception.InsufficientCapacityException;
import com.stockkeeper.exception.InvalidRequestException;
import com.stockkeeper.exception.InvalidTransitionException;
import com.stockkeeper.exception.ReservationNotFoundException;
import com.stockkeeper.exception.StockNotFoundException;
import com.stockkeeper.model.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Centralised exception handling for all REST controllers.
 *
 * Maps domain exceptions to appropriate HTTP status codes:
 *   400 Bad Request  → validation errors (Bean Validation, InvalidRequestException)
 *   404 Not Found    → stock or reservation not found
 *   409 Conflict     → insufficient capacity, invalid state transition
 *   500 Internal     → unexpected errors
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // -----------------------------------------------------------------------
    // 400 — Bean Validation failures (@Valid annotation)
    // -----------------------------------------------------------------------

    /**
     * Handles validation errors from @Valid @RequestBody.
     *
     * Spring throws MethodArgumentNotValidException when the @ValidHoldStockRequest
     * or @ValidTransitionRequest class-level validators fail. We extract all
     * violation messages and return them in the response body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<String> messages = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .toList();

        log.warn("Validation failed for {}: {}", request.getRequestURI(), messages);

        return ResponseEntity.badRequest().body(new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                messages,
                request.getRequestURI()
        ));
    }

    // -----------------------------------------------------------------------
    // 400 — Custom InvalidRequestException
    // -----------------------------------------------------------------------

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            InvalidRequestException ex, HttpServletRequest request) {

        log.warn("Invalid request for {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity.badRequest().body(new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                ex.getErrors(),
                request.getRequestURI()
        ));
    }

    // -----------------------------------------------------------------------
    // 404 — Not Found
    // -----------------------------------------------------------------------

    @ExceptionHandler(StockNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStockNotFound(
            StockNotFoundException ex, HttpServletRequest request) {

        log.info("Stock not found: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                List.of(ex.getMessage()),
                request.getRequestURI()
        ));
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReservationNotFound(
            ReservationNotFoundException ex, HttpServletRequest request) {

        log.info("Reservation not found: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                List.of(ex.getMessage()),
                request.getRequestURI()
        ));
    }

    // -----------------------------------------------------------------------
    // 409 — Conflict (insufficient capacity, invalid transitions)
    // -----------------------------------------------------------------------

    @ExceptionHandler(InsufficientCapacityException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientCapacity(
            InsufficientCapacityException ex, HttpServletRequest request) {

        log.warn("Insufficient capacity: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                List.of(ex.getMessage()),
                request.getRequestURI()
        ));
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(
            InvalidTransitionException ex, HttpServletRequest request) {

        log.warn("Invalid transition: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                List.of(ex.getMessage()),
                request.getRequestURI()
        ));
    }

    // -----------------------------------------------------------------------
    // 500 — Catch-all for unexpected errors
    // -----------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        log.error("Unexpected error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                List.of("An unexpected error occurred"),
                request.getRequestURI()
        ));
    }
}
