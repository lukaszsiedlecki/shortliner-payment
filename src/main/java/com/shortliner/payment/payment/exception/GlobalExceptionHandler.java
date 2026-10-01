package com.shortliner.payment.payment.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail handlePaymentNotFound(PaymentNotFoundException ex) {
        log.atWarn().addKeyValue("paymentId", ex.getPaymentId()).log("Payment not found");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Payment Not Found");
        return problem;
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ProblemDetail handleInvalidStateTransition(InvalidStateTransitionException ex) {
        log.warn("Invalid state transition: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid Payment State Transition");
        return problem;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
    public ProblemDetail handleBadRequest(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request");
        return problem;
    }

    /**
     * Fixed detail rather than the exception message: Jackson's parse errors
     * quote fragments of the request body back, which has no business ending
     * up in a response (or a log line) for a payments endpoint.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setTitle("Invalid Request");
        return problem;
    }

    /**
     * Spring MVC's own client errors (unknown path, wrong method) already
     * carry the right status and a ProblemDetail body. Without this handler
     * they'd fall through to {@link #handleGeneral} and come back as a 500
     * with an ERROR stack trace — noise in Loki and in the 5xx error rate.
     */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ProblemDetail> handleFrameworkClientError(ErrorResponse ex) {
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(ex.getBody());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
