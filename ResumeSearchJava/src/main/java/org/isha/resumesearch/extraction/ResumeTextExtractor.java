package org.isha.resumesearch.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/** Extracts plain text from resume files. PDF and Word (.doc/.docx) only for now - image OCR is a future addition. */
@Service
public class ResumeTextExtractor {

    // OOXML files (.docx) are zip archives and start with this signature - some resumes are actually
    // .docx content saved/renamed with a ".doc" extension, which the legacy WordExtractor below rejects.
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    public String extract(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        String extension = extensionOf(filename);
        return switch (extension) {
            case "pdf" -> extractPdf(file);
            case "docx" -> extractDocx(file);
            case "doc" -> extractDoc(file);
            default -> throw new UnsupportedFileTypeException(filename);
        };
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDoc(MultipartFile file) throws IOException {
        if (looksLikeZip(file)) {
            return extractDocx(file);
        }
        try (WordExtractor extractor = new WordExtractor(file.getInputStream())) {
            return extractor.getText();
        }
    }

    private boolean looksLikeZip(MultipartFile file) throws IOException {
        byte[] header = new byte[ZIP_SIGNATURE.length];
        try (var in = file.getInputStream()) {
            if (in.read(header) < header.length) {
                return false;
            }
        }
        return Arrays.equals(header, ZIP_SIGNATURE);
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new UnsupportedFileTypeException(filename);
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    public static class UnsupportedFileTypeException extends RuntimeException {
        public UnsupportedFileTypeException(String filename) {
            super("Unsupported file type: " + filename + " (only .pdf, .doc, .docx are supported)");
        }
    }
}
