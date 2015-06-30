package sodium

import sodium.impl.CellImpl
import sodium.impl.Node
import sodium.impl.StreamImpl
import sodium.impl.StreamWithSend

/**
 * If there's more than one firing in a single transaction, combine them into
 * one using the specified combining function.
 *
 * If the event firings are ordered, then the first will appear at the left
 * input of the combining function. In most common cases it's best not to
 * make any assumptions about the ordering, and the combining function would
 * ideally be commutative.
 */
public fun <A> Stream<A>.coalesce(transform: (A, A) -> A): Stream<A> {
    val thiz = this as StreamImpl<A>
    return Transaction.apply2 {
        thiz.coalesce(it, transform)
    }
}

/**
 * Merge two streams of events of the same type.
 *
 * In the case where two event occurrences are simultaneous (i.e. both
 * within the same transaction), both will be delivered in the same
 * transaction. If the event firings are ordered for some reason, then
 * their ordering is retained. In many common cases the ordering will
 * be undefined.
 */
public fun <A> Stream<A>.merge(other: Stream<A>): Stream<A> {
    val ea = this as StreamImpl<A>
    val eb = other as StreamImpl<A>
    val out = sodium.impl.StreamWithSend<A>()
    val left = Node<A>(0)
    val right = out.node
    val (changed, node_target) = left.linkTo(null, right)
    val handler = { trans: Transaction, value: A ->
        out.send(trans, value)
    }
    Transaction.apply2 {
        val l1 = ea.listen(left, it, false, handler)
        val l2 = eb.listen(right, it, false, handler)
        out.unsafeAddCleanup(l1).unsafeAddCleanup(l2)
    }

    return out.unsafeAddCleanup(object : Listener() {
        override fun unlisten() {
            left.unlinkTo(node_target)
        }
    })
}

/**
 * Merge two streams of events of the same type, combining simultaneous
 * event occurrences.
 *
 * In the case where multiple event occurrences are simultaneous (i.e. all
 * within the same transaction), they are combined using the same logic as
 * 'coalesce'.
 */
public fun <A> Stream<A>.merge(stream: Stream<A>, combine: (A, A) -> A): Stream<A> {
    return merge(stream).coalesce(combine)
}

/**
 * Create a behavior with the specified initial value, that gets updated
 * by the values coming through the event. The 'current value' of the behavior
 * is notionally the value as it was 'at the start of the transaction'.
 * That is, state updates caused by event firings get processed at the end of
 * the transaction.
 */
public fun <A> Stream<A>.hold(initValue: A): Cell<A> {
    val thiz = this as StreamImpl<A>
    return Transaction.apply2 {
        CellImpl(initValue, thiz.lastFiringOnly(it))
    }
}

public fun <A> Stream<A>.holdLazy(initValue: Lazy<A>): Cell<A> {
    val thiz = this as StreamImpl<A>
    return Transaction.apply2 {
        thiz.holdLazy(it, initValue)
    }
}

/**
 * Push each event occurrence in the list onto a new transaction.
 */
public fun <A, C : Collection<A>> Stream<C>.split(): Stream<A> {
    val out = StreamWithSend<A>()
    val thiz = this as StreamImpl<C>
    val listener = Transaction.apply2 {
        thiz.listen(out.node, it, false) { trans, events ->
            trans.post {
                for (event in events) {
                    val newTransaction = Transaction()
                    try {
                        out.send(newTransaction, event)
                    } finally {
                        newTransaction.close()
                    }
                }
            }
        }
    }
    return out.unsafeAddCleanup(listener)
}