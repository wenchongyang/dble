package io.mycat.plan.optimizer;

import io.mycat.plan.PlanNode;
import io.mycat.plan.node.JoinNode;

public final class JoinPreProcessor {
    private JoinPreProcessor() {
    }

    public static PlanNode optimize(PlanNode qtn) {
        qtn = findAndChangeRightJoinToLeftJoin(qtn);
        return qtn;
    }

    /**
     * change right join to left join.
     * <p>
     * <pre>
     * eg: A right join B on A.id = B.id
     * change to B left join B on A.id = B.id
     * </pre>
     */
    private static PlanNode findAndChangeRightJoinToLeftJoin(PlanNode qtn) {
        for (PlanNode child : qtn.getChildren()) {
            findAndChangeRightJoinToLeftJoin(child);
        }

        if (qtn instanceof JoinNode && ((JoinNode) qtn).isRightOuterJoin()) {
            JoinNode jn = (JoinNode) qtn;
            jn.exchangeLeftAndRight();
        }

        return qtn;
    }

}
