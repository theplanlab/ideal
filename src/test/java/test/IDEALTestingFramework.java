package test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;

import boomerang.accessgraph.AccessGraph;
import ideal.Analysis;
import ideal.ResultReporter;
import ideal.debug.IDEVizDebugger;
import ideal.debug.IDebugger;
import soot.Body;
import soot.Local;
import soot.SceneTransformer;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG;
import soot.jimple.infoflow.solver.cfg.InfoflowCFG;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;
import test.ExpectedResults.InternalState;
import test.core.selfrunning.AbstractTestingFramework;
import test.core.selfrunning.ImprecisionException;
import typestate.TypestateAnalysisProblem;
import typestate.TypestateChangeFunction;
import typestate.TypestateDomainValue;

public abstract class IDEALTestingFramework<State> extends AbstractTestingFramework{
	protected IInfoflowCFG icfg;
	protected long analysisTime;
	private IDEVizDebugger<TypestateDomainValue<State>> debugger;
	protected TestingResultReporter<State> testingResultReporter;

	protected abstract TypestateChangeFunction<State> createTypestateChangeFunction();

	protected Analysis<TypestateDomainValue<State>> createAnalysis() {
		return new Analysis<TypestateDomainValue<State>>(new TypestateAnalysisProblem<State>() {
			@Override
			public ResultReporter<TypestateDomainValue<State>> resultReporter() {
				return IDEALTestingFramework.this.testingResultReporter;
			}

			@Override
			public IInfoflowCFG icfg() {
				return icfg;
			}

			@Override
			public IDebugger<TypestateDomainValue<State>> debugger() {
				return IDEALTestingFramework.this.getDebugger();
			}

			@Override
			public TypestateChangeFunction<State> createTypestateChangeFunction() {
				return IDEALTestingFramework.this.createTypestateChangeFunction();
			}
		});
	}

	protected IDebugger<TypestateDomainValue<State>> getDebugger() {
		if(debugger == null)
			debugger = new IDEVizDebugger<>(ideVizFile, icfg);
		return debugger;
	}

	@Override
	protected SceneTransformer createAnalysisTransformer() throws ImprecisionException {
		return new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				icfg = new InfoflowCFG(new JimpleBasedInterproceduralCFG(true));
				Set<IExpectedResults> expectedResults = parseExpectedQueryResults(sootTestMethod);
				testingResultReporter = new TestingResultReporter(expectedResults);
				
				executeAnalysis();
				List<IExpectedResults> unsound = Lists.newLinkedList();
				List<IExpectedResults> imprecise = Lists.newLinkedList();
				for (IExpectedResults r : expectedResults) {
					if (!r.isSatisfied()) {
						unsound.add(r);
					}
				}
				for (IExpectedResults r : expectedResults) {
					if (r.isImprecise()) {
						imprecise.add(r);
					}
				}
				if (!unsound.isEmpty())
					throw new RuntimeException("Unsound results: " + unsound);
				IDEALTestingFramework.this.removeVizFile();
				if (!imprecise.isEmpty()) {
					throw new ImprecisionException("Imprecise results: " + imprecise);
				}
			}
		};
	}

	protected void executeAnalysis() {
		IDEALTestingFramework.this.createAnalysis().run();
	}

	private Set<IExpectedResults> parseExpectedQueryResults(SootMethod sootTestMethod) {
		Set<IExpectedResults> results = new HashSet<>();
		parseExpectedQueryResults(sootTestMethod, results, new HashSet<SootMethod>());
		return results;
	}

	private void parseExpectedQueryResults(SootMethod m, Set<IExpectedResults> queries, Set<SootMethod> visited) {
		if (!m.hasActiveBody() || visited.contains(m))
			return;
		visited.add(m);
		Body activeBody = m.getActiveBody();
		for (Unit callSite : icfg.getCallsFromWithin(m)) {
			for (SootMethod callee : icfg.getCalleesOfCallAt(callSite))
				parseExpectedQueryResults(callee, queries, visited);
		}
		for (Unit u : activeBody.getUnits()) {
			if (!(u instanceof Stmt))
				continue;

			Stmt stmt = (Stmt) u;
			if (!(stmt.containsInvokeExpr()))
				continue;
			InvokeExpr invokeExpr = stmt.getInvokeExpr();
			String invocationName = invokeExpr.getMethod().getName();
			if (!invocationName.startsWith("mayBeIn") && !invocationName.startsWith("mustBeIn"))
				continue;
			Value param = invokeExpr.getArg(0);
			if (!(param instanceof Local))
				continue;
			Local queryVar = (Local) param;
			AccessGraph val = new AccessGraph(queryVar, queryVar.getType());
			if (invocationName.startsWith("mayBeIn")) {
				if (invocationName.contains("Error"))
					queries.add(new MayBe(stmt, val, InternalState.ERROR));
				else
					queries.add(new MayBe(stmt, val, InternalState.ACCEPTING));
			} else if (invocationName.startsWith("mustBeIn")) {
				if (invocationName.contains("Error"))
					queries.add(new MustBe(stmt, val, InternalState.ERROR));
				else
					queries.add(new MustBe(stmt, val, InternalState.ACCEPTING));
			}
		}
	}

	/**
	 * The methods parameter describes the variable that a query is issued for.
	 * Note: We misuse the @Deprecated annotation to highlight the method in the
	 * Code.
	 */

	@Deprecated
	protected static void mayBeInErrorState(Object variable) {

	}

	@Deprecated
	protected static void mustBeInErrorState(Object variable) {

	}

	@Deprecated
	protected static void mayBeInAcceptingState(Object variable) {

	}

	@Deprecated
	protected void mustBeInAcceptingState(Object variable) {

	}

	/**
	 * This method can be used in test cases to create branching. It is not
	 * optimized away.
	 * 
	 * @return
	 */
	protected boolean staticallyUnknown() {
		return true;
	}

}
