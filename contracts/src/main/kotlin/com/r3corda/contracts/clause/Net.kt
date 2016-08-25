package com.r3corda.contracts.clause

import com.google.common.annotations.VisibleForTesting
import com.r3corda.contracts.asset.Obligation
import com.r3corda.contracts.asset.extractAmountsDue
import com.r3corda.contracts.asset.sumAmountsDue
import com.r3corda.core.contracts.*
import com.r3corda.core.contracts.clauses.MatchBehaviour
import com.r3corda.core.contracts.clauses.SingleClause
import java.security.PublicKey

/**
 * Common interface for the state subsets used when determining nettability of two or more states. Exposes the
 * underlying issued thing.
 */
interface NetState<P> {
    val template: Obligation.Terms<P>
}

/**
 * Subset of state, containing the elements which must match for two obligation transactions to be nettable.
 * If two obligation state objects produce equal bilateral net states, they are considered safe to net directly.
 * Bilateral states are used in close-out netting.
 */
data class BilateralNetState<P>(
        val partyKeys: Set<PublicKey>,
        override val template: Obligation.Terms<P>
) : NetState<P>

/**
 * Subset of state, containing the elements which must match for two or more obligation transactions to be candidates
 * for netting (this does not include the checks to enforce that everyone's amounts received are the same at the end,
 * which is handled under the verify() function).
 * In comparison to [BilateralNetState], this doesn't include the parties' keys, as ensuring balances match on
 * input and output is handled elsewhere.
 * Used in cases where all parties (or their proxies) are signing, such as central clearing.
 */
data class MultilateralNetState<P>(
        override val template: Obligation.Terms<P>
) : NetState<P>

/**
 * Clause for netting contract states. Currently only supports obligation contract.
 */
// TODO: Make this usable for any nettable contract states
open class NetClause<P> : SingleClause() {
    override val ifMatched: MatchBehaviour = MatchBehaviour.END
    override val ifNotMatched: MatchBehaviour = MatchBehaviour.CONTINUE
    override val requiredCommands: Set<Class<out CommandData>> = setOf(Obligation.Commands.Net::class.java)

    @Suppress("ConvertLambdaToReference")
    override fun verify(tx: TransactionForContract, commands: Collection<AuthenticatedObject<CommandData>>): Set<CommandData> {
        val command = commands.requireSingleCommand<Obligation.Commands.Net>()
        val groups = when (command.value.type) {
            NetType.CLOSE_OUT -> tx.groupStates { it: Obligation.State<P> -> it.bilateralNetState }
            NetType.PAYMENT -> tx.groupStates { it: Obligation.State<P> -> it.multilateralNetState }
        }
        for ((inputs, outputs, key) in groups) {
            verifyNetCommand(inputs, outputs, command, key)
        }
        return setOf(command.value)
    }

    /**
     * Verify a netting command. This handles both close-out and payment netting.
     */
    @VisibleForTesting
    fun verifyNetCommand(inputs: List<Obligation.State<P>>,
                         outputs: List<Obligation.State<P>>,
                         command: AuthenticatedObject<Obligation.Commands.Net>,
                         netState: NetState<P>) {
        val template = netState.template
        // Create two maps of balances from obligors to beneficiaries, one for input states, the other for output states.
        val inputBalances = extractAmountsDue(template, inputs)
        val outputBalances = extractAmountsDue(template, outputs)

        // Sum the columns of the matrices. This will yield the net amount payable to/from each party to/from all other participants.
        // The two summaries must match, reflecting that the amounts owed match on both input and output.
        requireThat {
            "all input states use the same template" by (inputs.all { it.template == template })
            "all output states use the same template" by (outputs.all { it.template == template })
            "amounts owed on input and output must match" by (sumAmountsDue(inputBalances) == sumAmountsDue(outputBalances))
        }

        // TODO: Handle proxies nominated by parties, i.e. a central clearing service
        val involvedParties = inputs.map { it.beneficiary }.union(inputs.map { it.obligor.owningKey }).toSet()
        when (command.value.type) {
        // For close-out netting, allow any involved party to sign
            NetType.CLOSE_OUT -> require(command.signers.intersect(involvedParties).isNotEmpty()) { "any involved party has signed" }
        // Require signatures from all parties (this constraint can be changed for other contracts, and is used as a
        // placeholder while exact requirements are established), or fail the transaction.
            NetType.PAYMENT -> require(command.signers.containsAll(involvedParties)) { "all involved parties have signed" }
        }
    }
}