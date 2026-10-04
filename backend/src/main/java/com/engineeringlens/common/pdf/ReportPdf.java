package com.engineeringlens.common.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.draw.LineSeparator;

/**
 * A small typesetting vocabulary shared by every Engiens report PDF: cover, sections, paragraphs, labelled
 * text, lists, key/value tables and code, in the product's restrained black/white/gray style with its own
 * fonts (Newsreader, Geist, Geist Mono; SIL Open Font License, bundled in resources/fonts). Content is always
 * plain text from persisted data; nothing here interprets or generates it.
 */
public final class ReportPdf {

    static final Color INK = new Color(0x11, 0x11, 0x11);
    static final Color MUTED = new Color(0x6b, 0x6b, 0x6b);
    static final Color LINE = new Color(0xd9, 0xd9, 0xd9);
    static final Color CODE_BG = new Color(0xf5, 0xf5, 0xf4);
    private static final int MAX_CODE_LINES = 220;

    private static final BaseFont SERIF = font("Newsreader16pt-Regular.ttf");
    private static final BaseFont SERIF_MEDIUM = font("Newsreader16pt-Medium.ttf");
    private static final BaseFont SERIF_ITALIC = font("Newsreader16pt-Italic.ttf");
    private static final BaseFont SANS = font("Geist-Regular.ttf");
    private static final BaseFont SANS_MEDIUM = font("Geist-Medium.ttf");
    private static final BaseFont SANS_SEMIBOLD = font("Geist-SemiBold.ttf");
    private static final BaseFont MONO = font("GeistMono-Regular.ttf");

    private final Document document;

    private ReportPdf(Document document) {
        this.document = document;
    }

    /**
     * @param footer the running footer's left text, e.g. "Engiens · Engineering Review · orders"
     */
    public static byte[] build(String title, String footer, Consumer<ReportPdf> content) {
        Document document = new Document(PageSize.A4, 56, 56, 60, 64);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setPageEvent(new Footer(footer));
        document.addTitle(title);
        document.addCreator("Engiens");
        document.open();
        content.accept(new ReportPdf(document));
        document.close();
        return out.toByteArray();
    }

    // ---- building blocks -------------------------------------------------------------------------

    /** The first page's heading block: a small kicker, the title, a subtitle and a metadata table. */
    public void cover(String kicker, String title, String subtitle, List<String[]> metadata) {
        add(paragraph("ENGIENS  ·  " + kicker.toUpperCase(), new Font(MONO, 8, Font.NORMAL, MUTED), 0, 18));
        add(paragraph(title, new Font(SERIF, 30, Font.NORMAL, INK), 0, 4));
        if (subtitle != null) {
            add(paragraph(subtitle, new Font(SERIF_ITALIC, 15, Font.NORMAL, MUTED), 0, 18));
        }
        keyValues(metadata);
        rule(10);
    }

    public void section(String title) {
        Paragraph p = paragraph(title, new Font(SERIF_MEDIUM, 17, Font.NORMAL, INK), 14, 0);
        p.setKeepTogether(true);
        add(p);
        rule(6);
    }

    public void subheading(String text) {
        Paragraph p = paragraph(text, new Font(SANS_SEMIBOLD, 10.5f, Font.NORMAL, INK), 7, 1);
        p.setKeepTogether(true);
        add(p);
    }

    /** A quiet uppercase label, e.g. "SOLID · MEDIUM CONFIDENCE". */
    public void label(String text) {
        add(paragraph(text.toUpperCase(), new Font(MONO, 7.5f, Font.NORMAL, MUTED), 2, 3));
    }

    public void paragraph(String text) {
        if (text != null && !text.isBlank()) {
            add(paragraph(text, body(), 0, 5));
        }
    }

    /** "Why it matters: …" with the label set apart. Skipped when the text is empty. */
    public void labeled(String label, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Paragraph p = new Paragraph();
        p.setLeading(13.5f);
        p.setSpacingAfter(4);
        p.add(new Chunk(label + "  ", new Font(SANS_MEDIUM, 9, Font.NORMAL, INK)));
        p.add(new Chunk(text, body()));
        add(p);
    }

    public void bullets(List<String> items) {
        if (items == null) {
            return;
        }
        List<String> shown = items.stream().filter(i -> i != null && !i.isBlank()).toList();
        for (int i = 0; i < shown.size(); i++) {
            Paragraph p = paragraph("–  " + shown.get(i), body(), 0, i == shown.size() - 1 ? 5 : 1.5f);
            p.setIndentationLeft(10);
            p.setFirstLineIndent(-10);
            add(p);
        }
    }

    /** Labelled bullet list, skipped when empty. */
    public void list(String label, List<String> items) {
        if (items != null && items.stream().anyMatch(i -> i != null && !i.isBlank())) {
            subheading(label);
            bullets(items);
        }
    }

    public void keyValues(List<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        PdfPTable table = new PdfPTable(new float[] { 1.1f, 3.9f });
        table.setWidthPercentage(100);
        table.setSpacingAfter(6);
        for (String[] row : rows) {
            table.addCell(cell(row[0].toUpperCase(), new Font(MONO, 7.5f, Font.NORMAL, MUTED)));
            table.addCell(cell(row[1] == null ? "—" : row[1], new Font(SANS, 9, Font.NORMAL, INK)));
        }
        add(table);
    }

    /** A file's content in monospace on a light panel. Long files are cut, and the cut is stated. */
    public void code(String path, String content) {
        if (content == null) {
            return;
        }
        List<String> lines = content.lines().toList();
        String shown = String.join("\n", lines.subList(0, Math.min(lines.size(), MAX_CODE_LINES)));
        if (lines.size() > MAX_CODE_LINES) {
            shown += "\n… " + (lines.size() - MAX_CODE_LINES) + " more lines not shown";
        }
        if (path != null) {
            label(path);
        }
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        PdfPCell cell = new PdfPCell(new Phrase(shown.replace("\t", "    "), new Font(MONO, 7.2f, Font.NORMAL, INK)));
        cell.setBackgroundColor(CODE_BG);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);
        cell.setLeading(10.2f, 0);
        table.addCell(cell);
        add(table);
    }

    public void newPage() {
        document.newPage();
    }

    // ---- internals -----------------------------------------------------------------------------

    private void rule(float spacingAfter) {
        LineSeparator line = new LineSeparator(0.5f, 100, LINE, Element.ALIGN_LEFT, -2);
        Paragraph p = new Paragraph(new Chunk(line));
        p.setLeading(6); // a rule, not a line of text
        p.setSpacingAfter(spacingAfter);
        add(p);
    }

    private static Font body() {
        return new Font(SANS, 9.2f, Font.NORMAL, INK);
    }

    private static Paragraph paragraph(String text, Font font, float before, float after) {
        Paragraph p = new Paragraph(text == null ? "" : text, font);
        p.setLeading(font.getSize() * 1.45f);
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        cell.setBorderWidth(0.4f);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(5);
        return cell;
    }

    private void add(Element element) {
        document.add(element);
    }

    private static BaseFont font(String file) {
        try (InputStream in = ReportPdf.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing font " + file);
            }
            return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "Engiens · … · page N" on every page. */
    private static final class Footer extends PdfPageEventHelper {
        private final String text;

        Footer(String text) {
            this.text = text;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            Font font = new Font(MONO, 7, Font.NORMAL, MUTED);
            float y = document.bottom() - 28;
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase(text, font), document.left(), y, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), font), document.right(), y, 0);
            canvas.setColorStroke(LINE);
            canvas.setLineWidth(0.4f);
            canvas.moveTo(document.left(), y + 10);
            canvas.lineTo(document.right(), y + 10);
            canvas.stroke();
        }
    }
}
