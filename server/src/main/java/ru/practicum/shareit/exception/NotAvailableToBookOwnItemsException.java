package ru.practicum.shareit.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotAvailableToBookOwnItemsException extends RuntimeException {
    public NotAvailableToBookOwnItemsException(String message) {
        super(message);
    }
}
