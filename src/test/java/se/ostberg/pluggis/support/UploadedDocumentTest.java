package se.ostberg.pluggis.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UploadedDocumentTest {

    public static byte[] docx(String documentXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void readsTextFromWordDocument() throws Exception {
        String xml = "<w:document><w:body>"
                + "<w:p><w:pPr><w:pStyle w:val=\"Rubrik1\"/></w:pPr><w:r><w:t>Kapitel 4: Fotosyntes</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Läs s. 40–48 </w:t></w:r><w:r><w:t>&amp; svara på frågorna</w:t></w:r></w:p>"
                + "<w:p/>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>klorofyll</w:t></w:r><w:r><w:tab/><w:t>gröna färgämnet</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:body></w:document>";

        UploadedDocument doc = UploadedDocument.of(docx(xml), "Prov NO.docx", "application/octet-stream");

        assertThat(doc.kind()).isEqualTo(UploadedDocument.Kind.WORD);
        assertThat(doc.text()).isEqualTo("Kapitel 4: Fotosyntes\nLäs s. 40–48 & svara på frågorna\nklorofyll\tgröna färgämnet");
        var content = new ObjectMapper().createArrayNode();
        doc.addTo(content, new ObjectMapper());
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).contains("Prov NO.docx").contains("klorofyll");
    }

    @Test
    void detectsImagesPdfAndTextAndRejectsOldWord() {
        assertThat(UploadedDocument.of(new byte[]{1, 2, 3}, "skarm.PNG", null).kind()).isEqualTo(UploadedDocument.Kind.IMAGE);
        assertThat(UploadedDocument.of(new byte[]{1, 2, 3}, "skarm.png", null).mediaType()).isEqualTo("image/png");
        assertThat(UploadedDocument.of(new byte[]{1, 2, 3}, "x", "application/pdf").kind()).isEqualTo(UploadedDocument.Kind.PDF);
        assertThat(UploadedDocument.of("Glosor: hund – dog".getBytes(StandardCharsets.UTF_8), "glosor.txt", "text/plain").text())
                .isEqualTo("Glosor: hund – dog");
        assertThatThrownBy(() -> UploadedDocument.of(new byte[]{1}, "gammal.doc", "application/msword"))
                .hasMessageContaining(".docx");
        assertThatThrownBy(() -> UploadedDocument.of(new byte[]{1}, "musik.mp3", "audio/mpeg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UploadedDocument.of(new byte[]{1, 2}, "inte-word.docx", null))
                .hasMessageContaining("Word");
    }
}
