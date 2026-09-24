package org.isha.resumesearch.dto;

import java.util.List;

public record BulkUploadResponse(List<BulkUploadResult> results) {
}
