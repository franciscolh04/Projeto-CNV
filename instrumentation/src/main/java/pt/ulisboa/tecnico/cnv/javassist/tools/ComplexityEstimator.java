package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javassist.CannotCompileException;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

public class ComplexityEstimator extends AbstractJavassistTool {

    // Thread-safe metric counter
    private static final ThreadLocal<Long> threadComplexity = ThreadLocal.withInitial(() -> 0L);
    
    // Cache: Saves the original weights of each offset before the bytecode is corrupted by the injections
    private static final Map<String, Map<Integer, Long>> pristineWeights = new ConcurrentHashMap<>();

    public ComplexityEstimator(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    // Accumulate weighted cost
    public static void addCost(long weight) {
        threadComplexity.set(threadComplexity.get() + weight);
    }

    // Retrieve and reset metric
    public static long getAndResetCost() {
        long cost = threadComplexity.get();
        threadComplexity.set(0L);
        return cost;
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        // Skip HTTP handlers
        if (block.behavior.getDeclaringClass().getName().endsWith("Handler")) {
            return;
        }

        try {
            String methodId = block.behavior.getLongName();

            // First Pass (Static Snapshot)
            // We record the weights of all opcodes BEFORE any injection occurs in the method
            if (!pristineWeights.containsKey(methodId)) {
                Map<Integer, Long> weights = new HashMap<>();
                MethodInfo methodInfo = block.behavior.getMethodInfo();
                CodeIterator iterator = methodInfo.getCodeAttribute().iterator();
                iterator.begin();
                while (iterator.hasNext()) {
                    int pos = iterator.next();
                    int opcode = iterator.byteAt(pos);
                    weights.put(pos, getOpcodeWeight(opcode));
                }
                pristineWeights.put(methodId, weights);
            }

            // Calculate block weight
            // We use the original snapshot, which is immune to the offset shifts caused by insertAt()
            long blockWeight = 0;
            int endPos = block.position + block.length;
            Map<Integer, Long> weights = pristineWeights.get(methodId);

            for (int p = block.position; p < endPos; p++) {
                if (weights.containsKey(p)) {
                    blockWeight += weights.get(p);
                }
            }

            // Hybrid Injection
            // We set the threshold to 10 to not lose the important multiplications of the Gray-Scott
            boolean isLoopHeader = block.entrances != null && block.entrances.length > 1;
            boolean isHeavyBlock = blockWeight >= 10;

            if (isLoopHeader || isHeavyBlock) {
                String injection = String.format("%s.addCost(%dL);", ComplexityEstimator.class.getName(), blockWeight);
                block.behavior.insertAt(block.line, injection);
            }

        } catch (Exception e) {
            throw new CannotCompileException(e);
        }
    }

    // Determine the computational weight of an opcode
    private long getOpcodeWeight(int opcode) {
        switch (opcode) {
            // 1. Memory Allocation
            case Opcode.NEW: case Opcode.NEWARRAY:
            case Opcode.ANEWARRAY: case Opcode.MULTIANEWARRAY:
                return 50L;

            // 2. Heavy Math (Division)
            case Opcode.DDIV: case Opcode.FDIV:
            case Opcode.DREM: case Opcode.FREM:
                return 25L;

            // 3. Method Invocations
            case Opcode.INVOKEVIRTUAL: case Opcode.INVOKESPECIAL:
            case Opcode.INVOKESTATIC: case Opcode.INVOKEINTERFACE:
            case Opcode.INVOKEDYNAMIC:
                return 15L;

            // 4. Double/Float Multiplications and Casts
            case Opcode.DMUL: case Opcode.FMUL:
            case Opcode.D2I: case Opcode.I2D:
                return 10L;

            // 5. Integer Division/Multiplication
            case Opcode.IDIV: case Opcode.IMUL:
                return 5L;

            // 6. Array Access (Memory Read/Write)
            case Opcode.DALOAD: case Opcode.DASTORE:
            case Opcode.AALOAD: case Opcode.AASTORE:
            case Opcode.IALOAD: case Opcode.IASTORE:
            case Opcode.CALOAD: case Opcode.CASTORE:
            case Opcode.BALOAD: case Opcode.BASTORE:
                return 4L;

            // 7. Basic Double/Float Arithmetic (Add/Sub/Compare)
            case Opcode.DADD: case Opcode.DSUB:
            case Opcode.FADD: case Opcode.FSUB:
            case Opcode.DCMPG: case Opcode.DCMPL:
                return 3L;

            // 8. Basic Integer Arithmetic & Bitwise
            case Opcode.IADD: case Opcode.ISUB:
            case Opcode.IINC:
            case Opcode.ISHL: case Opcode.ISHR:
                return 2L;

            // 9. Standard Loads, Stores, Jumps
            default:
                return 1L;
        }
    }
}