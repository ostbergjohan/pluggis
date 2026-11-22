package se.ostberg.pluggis.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * En uppladdad fil (skärmdump, foto, Word, PDF, text) och hur den skickas till Claude:
 * bilder som image-block, PDF som document-block, Word och text som läst text.
 */
public final class UploadedDocument {

    public enum Kind { IMAGE, PDF, WORD, TEXT }

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_PDF_BYTES = 20L * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 60_000;

    private static final Pattern PARAGRAPH = Pattern.compile("<w:p[ >].*?</w:p>|<w:p/>", Pattern.DOTALL);
    private static final Pattern TEXT_RUN = Pattern.compile("<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", Pattern.DOTALL);

    private final Kind kind;
    private final String filename;
    private final String mediaType;
    private final byte[] bytes;
    private final String text;

    private UploadedDocument(Kind kind, String filename, String mediaType, byte[] bytes, String text) {
        this.kind = kind;
        this.filename = filename;
        this.mediaType = mediaType;
        this.bytes = bytes;
        this.text = text;
    }

    /** Tolkar filen utifrån filändelse och content-type. Kastar IllegalArgumentException för okända/för stora filer. */
    public static UploadedDocument of(byte[] bytes, String filename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Tom fil");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        if (type.startsWith("image/") || name.matches(".*\\.(jpe?g|png|webp|gif)$")) {
            String media = imageType(type, name);
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Bilden är för stor (max 5 MB) – sänk upplösning/kvalitet");
            }
            return new UploadedDocument(Kind.IMAGE, filename, media, bytes, null);
        }
        if (type.equals("application/pdf") || name.endsWith(".pdf")) {
            if (bytes.length > MAX_PDF_BYTES) {
                throw new IllegalArgumentException("PDF:en är för stor (max 20 MB)");
            }
            return new UploadedDocument(Kind.PDF, filename, "application/pdf", bytes, null);
        }
        if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
            return new UploadedDocument(Kind.WORD, filename, type, bytes, limit(docxText(bytes)));
        }
        if (name.endsWith(".doc") || type.equals("application/msword")) {
            throw new IllegalArgumentException("Gamla .doc-filer stöds inte – spara som .docx eller PDF i Word");
        }
        if (type.startsWith("text/") || name.matches(".*\\.(txt|md|csv)$")) {
            return new UploadedDocument(Kind.TEXT, filename, "text/plain", bytes,
                    limit(TextFix.fixMojibake(new String(bytes, StandardCharsets.UTF_8))));
        }
        throw new IllegalArgumentException("Filtypen stöds inte. Använd bild, Word (.docx), PDF eller text.");
    }

    /** Läser texten ur word/document.xml i en .docx (stycken blir rader). */
    static String docxText(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    StringBuilder out = new StringBuilder();
                    Matcher p = PARAGRAPH.matcher(xml);
                    while (p.find()) {
                        Matcher t = TEXT_RUN.matcher(p.group());
                        StringBuilder line = new StringBuilder();
                        while (t.find()) {
                            line.append(t.group(1) != null ? unescape(t.group(1)) : t.group().startsWith("<w:tab") ? "\t" : "\n");
                        }
                        if (!line.isEmpty()) {
                            out.append(line).append('\n');
                        }
                    }
                    String text = out.toString().trim();
                    if (text.isEmpty()) {
                        throw new IllegalArgumentException("Word-filen innehöll ingen text");
                    }
                    return text;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Kunde inte läsa Word-filen", e);
        }
        throw new IllegalArgumentException("Det här ser inte ut som en Word-fil (.docx)");
    }

    /** Lägger filen som innehållsblock i ett Claude-meddelande. */
    public void addTo(ArrayNode content, ObjectMapper mapper) {
        switch (kind) {
            case IMAGE -> {
                ObjectNode block = content.addObject();
                block.put("type", "image");
                ObjectNode src = block.putObject("source");
                src.put("type", "base64");
                src.put("media_type", mediaType);
                src.put("data", Base64.getEncoder().encodeToString(bytes));
            }
            case PDF -> {
                ObjectNode block = content.addObject();
                block.put("type", "document");
                ObjectNode src = block.putObject("source");
                src.put("type", "base64");
                src.put("media_type", "application/pdf");
                src.put("data", Base64.getEncoder().encodeToString(bytes));
            }
            case WORD, TEXT -> {
                ObjectNode block = content.addObject();
                block.put("type", "text");
                block.put("text", "Innehåll i filen \"" + (filename == null ? "fil" : filename) + "\":\n\n" + text);
            }
        }
    }

    public Kind kind() { return kind; }
    public String filename() { return filename; }
    public String mediaType() { return mediaType; }
    /** Läst text för Word/text, annars null (bild/PDF läses av Claude). */
    public String text() { return text; }
    public byte[] bytes() { return bytes; }

    private static String imageType(String type, String name) {
        if (type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/webp") || type.equals("image/gif")) {
            return type;
        }
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.matches(".*\\.jpe?g$")) return "image/jpeg";
        throw new IllegalArgumentException("Bildformatet stöds inte: " + type + " (använd JPEG, PNG, WebP eller GIF)");
    }

    private static String limit(String s) {
        return s.length() > MAX_TEXT_CHARS ? s.substring(0, MAX_TEXT_CHARS) : s;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
