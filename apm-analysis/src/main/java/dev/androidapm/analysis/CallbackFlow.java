package dev.androidapm.analysis;

import heros.FlowFunction;
import heros.FlowFunctions;
import sootup.analysis.interprocedural.icfg.AbstractJimpleBasedICFG;
import sootup.analysis.interprocedural.ifds.DefaultJimpleIFDSTabulationProblem;
import sootup.analysis.interprocedural.ifds.JimpleIFDSSolver;
import sootup.callgraph.CallGraph;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.constant.MethodHandle;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.stmt.*;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.views.View;
import java.util.*;

/** Finite may-provenance for callbacks through locals, arguments and returns.
 * Heap/captured-field flow is intentionally not approximated as an exact binding. */
public final class CallbackFlow {
    public record Fact(Value local, MethodSignature target) {}
    public record Hit(SootMethod owner, Stmt statement, MethodSignature target) {}
    private static final Fact ZERO = new Fact(null, null);

    static final class Graph extends AbstractJimpleBasedICFG {
        private final Map<Stmt, Collection<SootMethod>> callees = new IdentityHashMap<>();
        private final Map<SootMethod, Collection<Stmt>> callers = new HashMap<>();
        Graph(View view, Collection<SootMethod> methods, CallGraph graph) {
            super(true);
            this.view = view;
            Map<MethodSignature, SootMethod> indexed = new HashMap<>();
            for (SootMethod m : methods) { indexed.put(m.getSignature(), m); initializeStmtToOwner(m); }
            for (SootMethod m : methods) {
                for (CallGraph.Call edge : graph.callsFrom(m.getSignature())) {
                    SootMethod target = indexed.get(edge.targetMethodSignature());
                    if (target == null) continue;
                    callees.computeIfAbsent(edge.invokableStmt(), k -> new LinkedHashSet<>()).add(target);
                    callers.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(edge.invokableStmt());
                }
            }
        }
        @Override public Collection<SootMethod> getCalleesOfCallAt(Stmt s) { return callees.getOrDefault(s, List.of()); }
        @Override public Collection<Stmt> getCallersOf(SootMethod m) { return callers.getOrDefault(m, List.of()); }
    }

    public static List<Hit> solve(View view, Collection<SootMethod> methods, CallGraph callGraph) {
        Graph graph = new Graph(view, methods, callGraph);
        Map<MethodSignature, SootMethod> indexed = new HashMap<>();
        methods.forEach(m -> indexed.put(m.getSignature(), m));
        Map<String, List<MethodSignature>> allocationTargets = new HashMap<>();
        methods.stream().filter(m -> Set.of("invoke", "run", "get", "accept", "apply", "Content").contains(m.getName()))
            .forEach(m -> allocationTargets.computeIfAbsent(m.getDeclaringClassType().toString(), key -> new ArrayList<>()).add(m.getSignature()));
        var problem = new DefaultJimpleIFDSTabulationProblem<Fact, Graph>(graph) {
            @Override protected Fact createZeroValue() { return ZERO; }
            @Override public int numThreads() { return 1; }
            @Override public Map<Stmt, Set<Fact>> initialSeeds() {
                Map<Stmt, Set<Fact>> seeds = new IdentityHashMap<>();
                methods.forEach(m -> graph.getStartPointsOf(m).forEach(s -> seeds.put(s, Set.of(ZERO))));
                return seeds;
            }
            @Override protected FlowFunctions<Stmt, Fact, SootMethod> createFlowFunctionsFactory() {
                return new FlowFunctions<>() {
                    private Set<Fact> assignment(Stmt s, Fact fact) {
                        Set<Fact> out = new HashSet<>();
                        if (!(s instanceof JAssignStmt a) || !(a.getLeftOp() instanceof Local)) return Set.of(fact);
                        if (fact == ZERO || !a.getLeftOp().equals(fact.local())) out.add(fact);
                        Value rhs = a.getRightOp();
                        if (rhs instanceof JCastExpr cast) rhs = cast.getOp();
                        if (fact != ZERO && rhs.equals(fact.local())) out.add(new Fact(a.getLeftOp(), fact.target()));
                        if (fact == ZERO) {
                            if (rhs instanceof JDynamicInvokeExpr dynamic &&
                                dynamic.getBootstrapMethodSignature().getDeclClassType().getFullyQualifiedName().equals("java.lang.invoke.LambdaMetafactory")) {
                                for (Value arg : dynamic.getBootstrapArgs()) {
                                    if (arg instanceof MethodHandle handle && handle.getReferenceSignature() instanceof MethodSignature signature && indexed.containsKey(signature))
                                        out.add(new Fact(a.getLeftOp(), signature));
                                }
                            } else if (rhs instanceof JNewExpr allocation) {
                                for (MethodSignature target : allocationTargets.getOrDefault(allocation.getType().toString(), List.of()))
                                    out.add(new Fact(a.getLeftOp(), target));
                            }
                        }
                        return out;
                    }
                    @Override public FlowFunction<Fact> getNormalFlowFunction(Stmt current, Stmt next) {
                        return fact -> assignment(current, fact);
                    }
                    @Override public FlowFunction<Fact> getCallFlowFunction(Stmt call, SootMethod callee) {
                        AbstractInvokeExpr invoke = call.asInvokableStmt().getInvokeExpr().orElseThrow();
                        return fact -> {
                            if (fact == ZERO) return Set.of(ZERO);
                            Set<Fact> out = new HashSet<>();
                            for (int i = 0; i < Math.min(callee.getParameterCount(), invoke.getArgCount()); i++)
                                if (invoke.getArg(i).equals(fact.local())) out.add(new Fact(callee.getBody().getParameterLocal(i), fact.target()));
                            if (!callee.isStatic() && invoke instanceof AbstractInstanceInvokeExpr instance && instance.getBase().equals(fact.local()))
                                out.add(new Fact(callee.getBody().getThisLocal(), fact.target()));
                            return out;
                        };
                    }
                    @Override public FlowFunction<Fact> getReturnFlowFunction(Stmt call, SootMethod callee, Stmt exit, Stmt site) {
                        return fact -> {
                            if (fact == ZERO) return Set.of(ZERO);
                            if (call instanceof JAssignStmt a && exit instanceof JReturnStmt r && r.getOp().equals(fact.local()))
                                return Set.of(new Fact(a.getLeftOp(), fact.target()));
                            return Set.of();
                        };
                    }
                    @Override public FlowFunction<Fact> getCallToReturnFlowFunction(Stmt call, Stmt site) {
                        return fact -> assignment(call, fact);
                    }
                };
            }
        };
        var solver = new JimpleIFDSSolver<Fact, Graph>(problem);
        solver.solve();
        List<Hit> result = new ArrayList<>();
        for (SootMethod m : methods) for (Stmt s : m.getBody().getStmts()) {
            if (!graph.isCallStmt(s)) continue;
            var invoke = s.asInvokableStmt().getInvokeExpr().orElseThrow();
            if (!(invoke instanceof AbstractInstanceInvokeExpr instance) ||
                !Set.of("invoke", "run", "get", "accept", "apply", "Content").contains(invoke.getMethodSignature().getName())) continue;
            for (Fact f : solver.ifdsResultsAt(s))
                if (f != ZERO && instance.getBase().equals(f.local())) result.add(new Hit(m, s, f.target()));
        }
        return result;
    }
}
