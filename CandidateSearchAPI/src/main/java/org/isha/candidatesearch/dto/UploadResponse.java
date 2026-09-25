package org.isha.candidatesearch.dto;

/** candidateId is the record actually written - an existing candidate's id when the upload was recognized as
 *  an updated resume of someone already on file (updatedExisting). */
public record UploadResponse(boolean status, String message, String candidateId, boolean updatedExisting) {
}
