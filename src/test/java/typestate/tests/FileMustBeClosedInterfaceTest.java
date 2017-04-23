package typestate.tests;

import org.junit.Test;

import targets.file.File;
import test.IDEALTestingFramework;
import typestate.TypestateChangeFunction;
import typestate.impl.statemachines.FileMustBeClosedStateMachine;
@SuppressWarnings("deprecation")
public class FileMustBeClosedInterfaceTest extends IDEALTestingFramework {
	@Test
	public void main() {
		FileMustBeClosedInterfaceTest target11 = new FileMustBeClosedInterfaceTest();
		File file = new File();
		Flow flow = (staticallyUnknown() ? target11.new ImplFlow1() : target11.new ImplFlow2());
		flow.flow(file);
		mayBeInErrorState(file);
		mayBeInAcceptingState(file);
	}

	public class ImplFlow1 implements Flow {

		@Override
		public void flow(File file) {
			file.open();
		}

	}

	public class ImplFlow2 implements Flow {

		@Override
		public void flow(File file) {
			file.close();
		}

	}

	private interface Flow {
		void flow(File file);
	}
	
	@Override
	protected TypestateChangeFunction createTypestateChangeFunction() {
		return new FileMustBeClosedStateMachine();
	}
}