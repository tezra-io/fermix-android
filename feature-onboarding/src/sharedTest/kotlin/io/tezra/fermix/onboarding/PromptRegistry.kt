package io.tezra.fermix.onboarding

import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import java.util.Collections

/**
 * The system's permission prompt as the tests answer it: with [answer], at once, through [onAnswer] and the
 * launcher's callback, keeping every permission asked for in [asked].
 */
internal class PromptRegistry(
    private val onAnswer: (Boolean) -> Unit,
) : ActivityResultRegistry() {
    @Volatile var answer = false
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        check(contract is ActivityResultContracts.RequestPermission) { "a prompt not for a permission: $contract" }
        asked += input as String
        val granted = answer
        onAnswer(granted)
        check(dispatchResult(requestCode, granted)) { "no launcher waited for the prompt's answer" }
    }
}
