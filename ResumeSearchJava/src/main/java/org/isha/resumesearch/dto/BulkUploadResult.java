package org.isha.resumesearch.dto;

public record BulkUploadResult(String resumeName, String uniquefileId, boolean status, String message) {
}
