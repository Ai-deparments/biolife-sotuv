package uz.biolife.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The single settings row: this week's promotion and new product, told to the analyst with every conversation. */
@Entity
@Table(name = "conv_settings")
public class ConvSettings {
    public static final int SINGLETON = 1;
    @Id private int id;
    @Column(columnDefinition = "text") private String promoText;
    @Column(columnDefinition = "text") private String newProductText;
    private Instant updatedAt;

    protected ConvSettings() {}
    public static ConvSettings empty() { ConvSettings s = new ConvSettings(); s.id = SINGLETON; return s; }
    public void update(String promo, String newProduct, Instant now) { promoText = trim(promo); newProductText = trim(newProduct); updatedAt = now; }
    private static String trim(String s) { return s == null || s.isBlank() ? null : s.strip().length() > 2000 ? s.strip().substring(0, 2000) : s.strip(); }
    public String getPromoText() { return promoText; }
    public String getNewProductText() { return newProductText; }
    public Instant getUpdatedAt() { return updatedAt; }
}
