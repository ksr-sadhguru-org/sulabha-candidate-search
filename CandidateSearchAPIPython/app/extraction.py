"""Extracts plain text from resume files. PDF and Word (.doc/.docx) only for now - image OCR is a future addition."""
import io
import re
import struct

import olefile
from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph
from pdfminer.high_level import extract_text

NO_TEXT_MESSAGE = "No text could be read from this file - it may be a scanned image. Scanned resumes aren't supported yet."

# OOXML files (.docx) are zip archives and start with this signature - some resumes are actually .docx content
# saved/renamed with a ".doc" extension, which the legacy parser below rejects.
_ZIP_SIGNATURE = b"PK\x03\x04"


class UnsupportedFileTypeError(Exception):
    def __init__(self, filename: str | None):
        super().__init__(f"Unsupported file type: {filename} (only .pdf, .doc, .docx are supported)")


def extract(filename: str | None, data: bytes) -> str:
    match _extension(filename):
        case "pdf":
            return _pdf(data)
        case "docx":
            return _docx(data)
        case "doc":
            return _docx(data) if data.startswith(_ZIP_SIGNATURE) else _doc(data)
        case _:
            raise UnsupportedFileTypeError(filename)


def _extension(filename: str | None) -> str:
    if not filename or "." not in filename:
        raise UnsupportedFileTypeError(filename)
    return filename.rsplit(".", 1)[1].lower()


def _pdf(data: bytes) -> str:
    """One line per text line, no blank lines between blocks - the layout the Java API's PDFBox gave."""
    text = re.sub(r"\n{2,}", "\n", extract_text(io.BytesIO(data)).replace("\x0c", "\n"))
    return text if not text.strip() or text.endswith("\n") else text + "\n"


def _docx(data: bytes) -> str:
    """Headers, then the body in order (paragraphs; table rows as tab-separated cells), then footers."""
    document = Document(io.BytesIO(data))
    lines: list[str] = []
    for section in document.sections:
        lines += [p.text for p in section.header.paragraphs if p.text]
    for child in document.element.body.iterchildren():
        if child.tag.endswith("}p"):
            lines.append(Paragraph(child, document).text)
        elif child.tag.endswith("}tbl"):
            lines += ["\t".join(cell.text for cell in row.cells) for row in Table(child, document).rows]
    for section in document.sections:
        lines += [p.text for p in section.footer.paragraphs if p.text]
    return "\n".join(lines) + "\n"


def _doc(data: bytes) -> str:
    """Legacy binary Word: the main text is stored as pieces (a piece table in the table stream) of 8-bit or UTF-16 text."""
    ole = olefile.OleFileIO(io.BytesIO(data))
    word = ole.openstream("WordDocument").read()
    table = ole.openstream("1Table" if struct.unpack_from("<H", word, 0x0A)[0] & 0x0200 else "0Table").read()
    chars_in_body = struct.unpack_from("<I", word, 0x4C)[0]
    clx_start, clx_size = struct.unpack_from("<II", word, 0x1A2)
    clx, i = table[clx_start:clx_start + clx_size], 0
    while clx[i] == 0x01:  # skip the property-modifier blocks before the piece table
        i += 3 + struct.unpack_from("<H", clx, i + 1)[0]
    pieces = (struct.unpack_from("<I", clx, i + 1)[0] - 4) // 12
    cps = struct.unpack_from(f"<{pieces + 1}I", clx, i + 5)
    text = []
    for n in range(pieces):
        fc = struct.unpack_from("<I", clx, i + 5 + 4 * (pieces + 1) + 8 * n + 2)[0]
        count = cps[n + 1] - cps[n]
        if fc & 0x40000000:  # compressed: one byte per character
            start = (fc & 0x3FFFFFFF) // 2
            text.append(word[start:start + count].decode("cp1252", errors="replace"))
        else:
            text.append(word[fc:fc + 2 * count].decode("utf-16-le", errors="replace"))
    return _clean_doc_text("".join(text)[:chars_in_body])


def _clean_doc_text(text: str) -> str:
    text = re.sub(r"\x13[^\x13\x14\x15]*\x15", "", text)  # fields with no result (page numbers, ...)
    text = re.sub(r"\x13[^\x13\x14\x15]*\x14", "", text)  # field code; its result stays
    for control, replacement in (("\r", "\n"), ("\x07", "\t"), ("\x0b", "\n"), ("\x0c", "\n"), ("\x1e", "-"), ("\x1f", "")):
        text = text.replace(control, replacement)
    return re.sub(r"[\x00-\x08\x0e-\x1f]", "", text)
