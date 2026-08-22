package com.eshop.catalog;

import com.eshop.catalog.domain.Category;
import com.eshop.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The category tree the storefront navigation is built from.
 *
 * <p>A flat list and a nested tree are indistinguishable to a test that only counts rows, so this
 * asserts the nesting itself: a parent comes back carrying its children, and a child does not also
 * appear at the root.
 */
class CategoryTreeTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    /** Two roots; the first has two children, the second has none. */
    private void taxonomy() {
        Category electronics = fixtures.category("electronics", "إلكترونيات", "Electronics", null);
        fixtures.category("home", "المنزل", "Home", null);
        fixtures.category("phones", "هواتف", "Phones", electronics);
        fixtures.category("laptops", "محمول", "Laptops", electronics);
    }

    @Test
    @DisplayName("the flat list returns every category at the top level")
    void flatListIsFlat() throws Exception {
        taxonomy();

        // GET /categories is the flat list and GET /categories/tree is the nested one. Both are
        // deliberate — a <select> in a form wants the flat shape — and the distinction is easy to
        // get wrong from the outside, so both are pinned here.
        mvc.perform(get("/api/v1/categories").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("the tree nests children under their parent")
    void treeIsNested() throws Exception {
        taxonomy();

        mvc.perform(get("/api/v1/categories/tree").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                // Two roots, not four entries flattened into one level.
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.slug=='electronics')].children.length()").value(2))
                .andExpect(jsonPath("$[?(@.slug=='home')].children.length()").value(0))
                // A child appears only beneath its parent.
                .andExpect(jsonPath("$[?(@.slug=='phones')]").isEmpty());
    }

    @Test
    @DisplayName("the materialised path is maintained by the database trigger")
    void pathIsComputed() {
        taxonomy();

        assertThat(jdbc.queryForObject(
                "SELECT path FROM categories WHERE slug = 'phones'", String.class))
                .isEqualTo("/electronics/phones");
        assertThat(jdbc.queryForObject(
                "SELECT level FROM categories WHERE slug = 'phones'", Integer.class))
                .isEqualTo(1);
    }
}
