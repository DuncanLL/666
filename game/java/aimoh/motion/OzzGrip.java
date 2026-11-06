package aimoh.motion;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** Standard JDK FFM call into the unchanged ozz two-bone job, no IK equations. */
public final class OzzGrip implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final MethodHandle solve;
    private final MemorySegment matrices = arena.allocate(JAVA_FLOAT, 48);
    private final MemorySegment target = arena.allocate(JAVA_FLOAT, 3);
    private final MemorySegment pole = arena.allocate(JAVA_FLOAT, 3);
    private final MemorySegment axis = arena.allocate(JAVA_FLOAT, 3);
    private final MemorySegment rotations = arena.allocate(JAVA_FLOAT, 8);

    public OzzGrip(Path library) {
        solve = Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup(library, arena).findOrThrow("aimoh_two_bone"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                                   JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS));
    }

    public synchronized int solve(float[] jointMatrices, float[] targetPosition,
                                  float[] poleDirection, float[] bendAxis,
                                  float weight, float soften, float twist, float[] result) {
        if (jointMatrices.length != 48 || targetPosition.length != 3 ||
            poleDirection.length != 3 || bendAxis.length != 3 || result.length != 8)
            throw new IllegalArgumentException("Ozz input shape differs from C ABI");
        matrices.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer().put(jointMatrices);
        target.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer().put(targetPosition);
        pole.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer().put(poleDirection);
        axis.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer().put(bendAxis);
        try {
            int status = (int)solve.invokeExact(matrices, target, pole, axis,
                weight, soften, twist, rotations);
            if (status >= 0) rotations.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer().get(result);
            return status;
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot invoke ozz component", error);
        }
    }

    public synchronized void close() { arena.close(); }
}
