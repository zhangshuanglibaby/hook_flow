package com.hookflow.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PageParameters(int page, int size) {
    public PageParameters {
        if (page < 1 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    public static PageParameters parse(String page, String size) {
        return new PageParameters(page == null ? 1 : positiveInt(page), size == null ? 20 : positiveInt(size));
    }

    public long offset() { return ((long) page - 1) * size; }

    private static int positiveInt(String value) {
        if (!value.matches("[1-9][0-9]*")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }
}
