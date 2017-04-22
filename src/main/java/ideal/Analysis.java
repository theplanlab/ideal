package ideal;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;

import boomerang.accessgraph.AccessGraph;
import boomerang.accessgraph.WrappedSootField;
import heros.EdgeFunction;
import heros.edgefunc.EdgeIdentity;
import heros.solver.PathEdge;
import ideal.debug.IDebugger;
import ideal.debug.JSONDebugger;
import ideal.edgefunction.AnalysisEdgeFunctions;
import ideal.pointsofaliasing.PointOfAlias;
import ideal.pointsofaliasing.ReturnEvent;
import soot.MethodOrMethodContext;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.solver.cfg.BackwardsInfoflowCFG;
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.util.queue.QueueReader;

public class Analysis<V> {

	/**
	 * Specifies the budget per seed in milliseconds.
	 */
	public static long BUDGET = 300000000;

	/**
	 * Specifies the budget per alias query in milliseconds.
	 */
	public static long ALIAS_BUDGET = 500;
	public static boolean ENABLE_STATIC_FIELDS = true;
	public static boolean ENABLE_STRONG_UPDATES = true;
	public static boolean ALIASING = true;
	public static boolean ALIASING_FOR_STATIC_FIELDS = false;
	public static boolean SEED_IN_APPLICATION_CLASS_METHOD = false;

	private final IDebugger<V> debugger;
	private static Stopwatch START_TIME;

	private AnalysisContext<V> context;
	private Set<PathEdge<Unit, AccessGraph>> initialSeeds = new HashSet<>();
	private Set<PointOfAlias<V>> seenPOA = new HashSet<>();
	private final IInfoflowCFG icfg;
	protected final AnalysisProblem<V> problem;
	private final AnalysisEdgeFunctions<V> edgeFunc;
	private BackwardsInfoflowCFG bwicfg;
	private ResultReporter<V> reporter;

	private Map<PathEdge<Unit, AccessGraph>, EdgeFunction<V>> pathEdgeToEdgeFunc = new HashMap<>();

	public Analysis(AnalysisProblem<V> problem, IInfoflowCFG icfg, ResultReporter<V> reporter) {
		this.edgeFunc = problem.edgeFunctions();
		this.problem = problem;
		this.icfg = icfg;
		this.bwicfg = new BackwardsInfoflowCFG(icfg);
		// this.debugger = new NullDebugger<V>();
		this.debugger = new JSONDebugger<V>(new File("visualization/data.js"), icfg);
		this.reporter = reporter;
	}

	public Analysis(AnalysisProblem<V> problem, IInfoflowCFG icfg, ResultReporter<V> reporter, IDebugger<V> debugger) {
		this.edgeFunc = problem.edgeFunctions();
		this.problem = problem;
		this.icfg = icfg;
		this.bwicfg = new BackwardsInfoflowCFG(icfg);
		this.debugger = debugger;
		this.reporter = reporter;
	}

	public void run() {
		if (!ENABLE_STRONG_UPDATES) {
			System.err.println("Strong updates are disabled.");
		}
		WrappedSootField.TRACK_TYPE = false;
		WrappedSootField.TRACK_STMT = false;
		initialSeeds = computeSeeds();
		if (initialSeeds.isEmpty())
			System.err.println("No seeds found!");
		else
			System.err.println("Analysing " + initialSeeds.size() + " seeds!");
		debugger.beforeAnalysis();
		for (PathEdge<Unit, AccessGraph> seed : initialSeeds) {
			analysisForSeed(seed);
		}
		debugger.afterAnalysis();
	}

	private void analysisForSeed(final PathEdge<Unit, AccessGraph> seed) {
		boolean timeout = false;
		debugger.startWithSeed(seed);
		timeout = false;
		context = new AnalysisContext<>(icfg, bwicfg, edgeFunc, debugger);
		START_TIME = Stopwatch.createStarted();
		AnalysisSolver<V> solver = new AnalysisSolver<>(context.icfg(), context, edgeFunc);
		context.setSolver(solver);
		try {
			System.out.println("================== STARTING PHASE 1 ==================");
			phase1(seed, solver);
			solver.destroy();
			solver = new AnalysisSolver<>(context.icfg(), context, edgeFunc);
			System.out.println("================== STARTING PHASE 2 ==================");
			phase2(seed, solver);
			context.setSolver(solver);
		} catch (AnalysisTimeoutException e) {
			System.out.println("Timeout of IDEAL");
			timeout = true;
			debugger.onAnalysisTimeout(seed);
		}
		reporter.onSeedFinished(seed, solver);
		context.destroy();
		solver.destroy();
	}

	private void phase1(PathEdge<Unit, AccessGraph> seed, AnalysisSolver<V> solver) {
		debugger.startPhase1WithSeed(seed, solver);
		Set<PathEdge<Unit, AccessGraph>> worklist = new HashSet<>();
		if (icfg.isExitStmt(seed.getTarget()) || icfg.isCallStmt(seed.getTarget())) {
			worklist.add(seed);
		} else {
			for (Unit u : icfg.getSuccsOf(seed.getTarget())) {
				worklist.add(new PathEdge<Unit, AccessGraph>(seed.factAtSource(), u, seed.factAtTarget()));
			}
		}
		while (!worklist.isEmpty()) {
			debugger.startForwardPhase(worklist);
			for (PathEdge<Unit, AccessGraph> s : worklist) {
				EdgeFunction<V> func = pathEdgeToEdgeFunc.get(s);
				if (func == null)
					func = EdgeIdentity.v();
				solver.injectPhase1Seed(s.factAtSource(), s.getTarget(), s.factAtTarget(), func);
			}
			worklist.clear();
			Set<PointOfAlias<V>> pointsOfAlias = context.getAndClearPOA();
			debugger.startAliasPhase(pointsOfAlias);
			for (PointOfAlias<V> p : pointsOfAlias) {
				if (seenPOA.contains(p))
					continue;
				seenPOA.add(p);
				debugger.solvePOA(p);
				Collection<PathEdge<Unit, AccessGraph>> edges = p.getPathEdges(context);
				worklist.addAll(edges);
				if (p instanceof ReturnEvent) {
					ReturnEvent<V> returnEvent = (ReturnEvent) p;
					for (PathEdge<Unit, AccessGraph> edge : edges) {
						pathEdgeToEdgeFunc.put(edge, returnEvent.getEdgeFunction());
					}
				}
				Analysis.checkTimeout();
			}
		}
		debugger.finishPhase1WithSeed(seed, solver);
	}

	private void phase2(PathEdge<Unit, AccessGraph> s, AnalysisSolver<V> solver) {
		debugger.startPhase2WithSeed(s, solver);
		context.enableIDEPhase();
		if (icfg.isExitStmt(s.getTarget()) || icfg.isCallStmt(s.getTarget())) {
			solver.injectPhase2Seed(s.factAtSource(), s.getTarget(), s.factAtTarget(), context);
		} else {
			for (Unit u : icfg.getSuccsOf(s.getTarget())) {
				solver.injectPhase2Seed(s.factAtSource(), u, s.factAtTarget(), context);
			}
		}
		solver.runExecutorAndAwaitCompletion();
		HashMap<Unit, Set<AccessGraph>> map = new HashMap<Unit, Set<AccessGraph>>();
		HashSet<AccessGraph> hashSet = new HashSet<>();
		hashSet.add(s.factAtSource());
		for (Unit sp : icfg.getStartPointsOf(icfg.getMethodOf(s.getTarget()))) {
			map.put(sp, hashSet);
		}
		solver.computeValues(map);
		debugger.finishPhase2WithSeed(s, solver);
	}

	private Set<PathEdge<Unit, AccessGraph>> computeSeeds() {
		Set<PathEdge<Unit, AccessGraph>> seeds = new HashSet<>();
		ReachableMethods rm = Scene.v().getReachableMethods();
		QueueReader<MethodOrMethodContext> listener = rm.listener();
		while (listener.hasNext()) {
			MethodOrMethodContext next = listener.next();
			seeds.addAll(computeSeeds(next.method()));
		}
		return seeds;
	}

	private Collection<? extends PathEdge<Unit, AccessGraph>> computeSeeds(SootMethod method) {
		Set<PathEdge<Unit, AccessGraph>> seeds = new HashSet<>();

		if (!method.hasActiveBody())
			return seeds;
		for (Unit u : method.getActiveBody().getUnits()) {
			Collection<SootMethod> calledMethods = (icfg.isCallStmt(u) ? icfg.getCalleesOfCallAt(u)
					: new HashSet<SootMethod>());
			for (AccessGraph fact : problem.generate(method, u, calledMethods)) {
				PathEdge<Unit, AccessGraph> pathEdge = new PathEdge<Unit, AccessGraph>(InternalAnalysisProblem.ZERO, u,
						fact);
				seeds.add(pathEdge);
			}
		}
		return seeds;

	}

	public static void checkTimeout() {
		if ((Analysis.START_TIME.elapsed(TimeUnit.MILLISECONDS)) > Analysis.BUDGET)
			throw new AnalysisTimeoutException();
	}
}
