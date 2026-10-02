package dev.neko.app.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neko.app.NekoApplication
import dev.neko.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionDialogRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editedAmountSurvivesActivityStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val transaction = Transaction(
            occurredAt=1_791_438_000_000,
            amountPaise=12_345,
            direction=Direction.DEBIT,
            account="Cash",
            merchant="Coffee",
            review=ReviewStatus.CONFIRMED,
            status=PaymentStatus.POSTED,
        )
        restoration.setContent {
            NekoTheme(mode="light") {
                TransactionDialog(
                    tx=transaction,
                    state=NekoState(),
                    onDismiss={},
                    onSave={ _,_,_,_,_,_,_,_,_,_-> },
                )
            }
        }

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("999.00")
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodes(hasSetTextAction()).onFirst().assertTextEquals("999.00")
    }

    @Test fun financeInsightsLabelsAnUnreconciledSmsTotalAsIncomplete() {
        val transaction = Transaction(
            occurredAt=1_791_438_000_000,
            amountPaise=12_345,
            direction=Direction.DEBIT,
            account="ICICI · 1234",
            merchant="Coffee",
            category=Category.FOOD,
            source=Source.SMS,
            review=ReviewStatus.CONFIRMED,
            status=PaymentStatus.POSTED,
        )
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NekoApplication
        val model = NekoViewModel(app)
        compose.setContent { NekoTheme(mode="light") { InsightsScreen(NekoState(loading=false,transactions=listOf(transaction)),model) } }

        compose.onNodeWithText("Coverage incomplete").assertExists()
    }
}
