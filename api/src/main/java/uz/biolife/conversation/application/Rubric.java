package uz.biolife.conversation.application;

import java.util.List;
import java.util.Optional;

/** The scoring rubric (total 100). Same codes and weights as rating's Criteria — ConversationRubricTests keeps them equal. */
public final class Rubric {
    public record Item(String code, String title, int weight) {}

    public static final List<Item> ITEMS = List.of(
        new Item("salom", "Salomlashish va tanishtirish", 10),
        new Item("ehtiyoj", "Ehtiyojni aniqlash", 20),
        new Item("taklif", "Mahsulot taklifi", 20),
        new Item("aksiya", "Aksiya aytildi", 15),
        new Item("yangi", "Yangi mahsulot ko'rsatildi", 10),
        new Item("etiroz", "E'tiroz bilan ishlash", 15),
        new Item("yakun", "Yakun va buyurtma", 10));

    private Rubric() {}
    public static Optional<Item> get(String code) { return ITEMS.stream().filter(i -> i.code().equals(code)).findFirst(); }
}
