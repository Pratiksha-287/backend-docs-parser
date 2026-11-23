package com.gupta.liferayanalyzer.exception;

public class LiferayApiException extends RuntimeException {

    private final int httpStatus;

    public LiferayApiException(String message) {
        this(message, 0);
    }

    public LiferayApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public LiferayApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
    }

    /** HTTP status returned by Liferay, or 0 when not applicable. */
    public int getHttpStatus() {
        return httpStatus;
    }
}
