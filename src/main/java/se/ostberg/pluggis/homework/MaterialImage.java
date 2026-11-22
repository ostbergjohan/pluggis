package se.ostberg.pluggis.homework;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

/** Förminskad sidbild till ett material (samma id som materialet). Hämtas bara när den ska visas. */
@Entity
public class MaterialImage {

    @Id
    private Long id;

    @Lob
    @Column(length = 4_000_000)
    private byte[] data;

    protected MaterialImage() {
    }

    public MaterialImage(Long materialId, byte[] data) {
        this.id = materialId;
        this.data = data;
    }

    public Long getId() { return id; }
    public byte[] getData() { return data; }
}
