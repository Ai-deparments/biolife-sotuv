package uz.biolife.rating.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Criterion catalogue used to phrase missions. Weights mirror the analysis module's standard template
 * (jami 100). When analysis exposes criteria via events this map is replaced by that feed.
 */
public final class Criteria {
    public record Def(String code, String title, int weight, String advice) {}

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    static {
        put("salom", "Salomlashish va tanishtirish", 10, "Kirishda ism, firma va maqsadni ayting.");
        put("ehtiyoj", "Ehtiyojni aniqlash", 20, "Kirishda 4–5 savol bering: qaysi ta'm ketyapti, qancha qoldi, qachon oxirgi dostavka edi.");
        put("taklif", "Mahsulot taklifi", 20, "Assortimentdan bir nechta mahsulotni nomma-nom taklif qiling: suv, limonad, sovuq choy.");
        put("aksiya", "Aksiya aytildi", 15, "Kirishdanoq joriy aksiya shartini to'liq ayting.");
        put("yangi", "Yangi mahsulot ko'rsatildi", 10, "Yangi mahsulotni qo'lga olib ko'rsating va uni qayerga qo'yishni taklif qiling.");
        put("etiroz", "E'tiroz bilan ishlash", 15, "«Olmayman» dan keyin kamida bitta dalil keltiring: qo'shni do'kon misoli.");
        put("yakun", "Yakun va buyurtma", 10, "Miqdor va yetkazish kunini aniq aytib yakunlang.");
    }
    private static void put(String code, String title, int weight, String advice) { DEFS.put(code, new Def(code, title, weight, advice)); }
    private Criteria() {}
    public static Map<String, Def> all() { return DEFS; }
    public static Def get(String code) { return DEFS.get(code); }
}
