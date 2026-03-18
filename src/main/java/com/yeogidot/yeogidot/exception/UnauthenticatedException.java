package com.yeogidot.yeogidot.exception;

/**
 * JWT는 유효하지만 DB에 유저가 없을 때 발생하는 예외 (401)
 */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}
