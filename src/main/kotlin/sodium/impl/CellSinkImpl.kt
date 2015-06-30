package sodium.impl

import sodium.CellSink
import sodium.Transaction

public class CellSinkImpl<A>(initValue: A) : CellSink<A>, CellImpl<A>(initValue, StreamWithSend<A>()) {
    override fun send(a: A) {
        Transaction.apply2 {
            if (Transaction.inCallback > 0)
                throw RuntimeException("You are not allowed to use send() inside a Sodium callback")
            (stream as StreamWithSend<A>).send(it, a)
        }
    }
}