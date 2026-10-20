package uz.biolife.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uz.biolife.conversation.application.Rubric;
import uz.biolife.rating.domain.Criteria;

/** The analyst's rubric and rating's criteria must stay the same codes and weights (total 100). */
class ConversationRubricTests {
    @Test
    void rubricMatchesRatingCriteria() {
        Map<String, Integer> rubric = Rubric.ITEMS.stream().collect(Collectors.toMap(Rubric.Item::code, Rubric.Item::weight));
        Map<String, Integer> rating = Criteria.all().values().stream().collect(Collectors.toMap(Criteria.Def::code, Criteria.Def::weight));
        assertThat(rubric).isEqualTo(rating);
        assertThat(rubric.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }
}
