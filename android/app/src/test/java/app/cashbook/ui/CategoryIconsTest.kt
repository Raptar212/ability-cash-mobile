package app.cashbook.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryIconsTest {
    /** Каждая иконка набора собирается, у каждой есть название для TalkBack. */
    @Test fun everyIconBuildsAndHasName() {
        assertEquals(CATEGORY_ICON_PATHS.keys, CATEGORY_ICON_NAMES.keys)
        for (slug in CATEGORY_ICON_PATHS.keys) assertNotNull(slug, categoryIcon(slug))
    }

    /** Не выбрана или убрана из набора — null: на кружке инициалы, а не падение. */
    @Test fun missingOrUnknownSlugGivesNull() {
        assertNull(categoryIcon(null))
        assertNull(categoryIcon("no-such-icon"))
    }
}
