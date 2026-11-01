package de.noizycologne.backend.api;

/** Stable, user-facing error contract for the local and staging API. */
public record ApiErrorResponse(String code, String message, int status, boolean retryable) { }
