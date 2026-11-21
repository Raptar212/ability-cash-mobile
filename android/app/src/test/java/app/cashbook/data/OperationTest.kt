package app.cashbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationTest {
    private fun bad(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun typeFromSides() {
        assertEquals(OperationType.EXPENSE, Operation(date = 0, expenseAccountId = 1, expenseAmount = 10).type)
        assertEquals(OperationType.INCOME, Operation(date = 0, incomeAccountId = 1, incomeAmount = 10).type)
        assertEquals(
            OperationType.TRANSFER,
            Operation(date = 0, expenseAccountId = 1, expenseAmount = 10, incomeAccountId = 2, incomeAmount = 7).type,
        )
    }

    @Test fun rejectsInvalid() {
        bad { Operation(date = 0) }
        bad { Operation(date = 0, expenseAccountId = 1) }
        bad { Operation(date = 0, expenseAmount = 10) }
        bad { Operation(date = 0, incomeAccountId = 1) }
        bad { Operation(date = 0, incomeAmount = 10) }
        bad { Operation(date = 0, expenseAccountId = 1, expenseAmount = 0) }
        bad { Operation(date = 0, incomeAccountId = 1, incomeAmount = -5) }
        bad { Operation(date = 0, expenseAccountId = 1, expenseAmount = 10, incomeAccountId = 1, incomeAmount = 10) }
        bad { Operation(date = 0, expenseAccountId = 1, expenseAmount = 10, baseAmount = 0) }
        bad { Operation(date = 0, expenseAccountId = 1, expenseAmount = 10, incomeAccountId = 2, incomeAmount = 7, baseAmount = 5) }
        Operation(date = 0, incomeAccountId = 1, incomeAmount = 10, baseAmount = 400) // доход с пересчётом — можно
    }
}
