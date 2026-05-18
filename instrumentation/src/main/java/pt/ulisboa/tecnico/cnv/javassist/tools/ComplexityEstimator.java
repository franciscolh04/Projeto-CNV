package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;
import javassist.CannotCompileException;
import javassist.CtBehavior;

public class ComplexityEstimator extends AbstractJavassistTool {

    // Thread-safe metric counter
    private static final ThreadLocal<Long> threadComplexity = ThreadLocal.withInitial(() -> 0L);

    public ComplexityEstimator(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    // Accumulate instruction cost
    public static void addCost(int basicBlockLength) {
        threadComplexity.set(threadComplexity.get() + basicBlockLength);
    }

    // Retrieve and reset metric
    public static long getAndResetCost() {
        long cost = threadComplexity.get();
        threadComplexity.set(0L);
        return cost;
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        
        // Inject cost accumulation code
        String injection = String.format("%s.addCost(%s);", ComplexityEstimator.class.getName(), block.getLength());
        block.behavior.insertAt(block.line, injection);
    }
}