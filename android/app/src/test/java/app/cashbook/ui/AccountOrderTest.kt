package app.cashbook.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountOrderTest {
    // Счета приходят «по имени»: id 1..4.
    private val byName = listOf(1L, 2L, 3L, 4L)

    @Test fun userOrderFirstThenNewInNameOrder() {
        assertEquals(listOf(3L, 1L, 2L, 4L), AccountOrder.sort(byName, listOf(3L, 1L)) { it })
    }

    @Test fun emptyOrderKeepsNameOrder() {
        assertEquals(byName, AccountOrder.sort(byName, emptyList()) { it })
    }

    @Test fun removedAccountsInOrderIgnored() {
        assertEquals(listOf(2L, 1L, 3L, 4L), AccountOrder.sort(byName, listOf(9L, 2L, 1L)) { it })
    }
}
