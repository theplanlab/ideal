package typestate.impl.printstream;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import boomerang.accessgraph.AccessGraph;
import heros.EdgeFunction;
import heros.solver.Pair;
import soot.Local;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.infoflow.solver.cfg.InfoflowCFG;
import typestate.TransitionFunction;
import typestate.TypestateChangeFunction;
import typestate.TypestateDomainValue;
import typestate.finiteautomata.MatcherStateMachine;
import typestate.finiteautomata.MatcherTransition;
import typestate.finiteautomata.MatcherTransition.Parameter;
import typestate.finiteautomata.MatcherTransition.Type;
import typestate.finiteautomata.State;
import typestate.finiteautomata.Transition;

public class PrintStreamStateMachine extends MatcherStateMachine
    implements TypestateChangeFunction {

  private MatcherTransition initialTrans;
  private InfoflowCFG icfg;

  public static enum States implements State {
    NONE, CLOSED, ERROR;

    @Override
    public boolean isErrorState() {
      return this == ERROR;
    }

    @Override
    public boolean isInitialState() {
      return this == CLOSED;
    }
  }

  PrintStreamStateMachine(InfoflowCFG icfg) {
    this.icfg = icfg;
    initialTrans = new MatcherTransition(States.NONE, closeMethods(),Parameter.This, States.CLOSED, Type.OnReturn);
    addTransition(initialTrans);
    addTransition(
        new MatcherTransition(States.CLOSED, closeMethods(),Parameter.This, States.CLOSED, Type.OnReturn));
    addTransition(new MatcherTransition(States.CLOSED, readMethods(),Parameter.This, States.ERROR, Type.OnReturn));
  }

  private Set<SootMethod> closeMethods() {
    return selectMethodByName(getSubclassesOf("java.io.PrintStream"), "close");
  }
  @Override
	public boolean seedInApplicationClass() {
		return false;
	}

  private Set<SootMethod> readMethods() {
    List<SootClass> subclasses = getSubclassesOf("java.io.PrintStream");
    Set<SootMethod> closeMethods = closeMethods();
    Set<SootMethod> out = new HashSet<>();
    for (SootClass c : subclasses) {
      for (SootMethod m : c.getMethods())
        if (m.isPublic() && !closeMethods.contains(m) && !m.isStatic())
          out.add(m);
    }
    return out;
  }


  @Override
  public Collection<Pair<AccessGraph, EdgeFunction<TypestateDomainValue>>> generate(Unit unit,
      Collection<SootMethod> calledMethod) {
    return this.generateThisAtAnyCallSitesOf(unit, calledMethod, closeMethods(), initialTrans);
  }

}

