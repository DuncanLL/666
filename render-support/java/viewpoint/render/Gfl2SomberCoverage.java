package viewpoint.render;

import org.lwjgl.opengl.*;
import viewpoint.platform.Gfl2NativePackSelection;

public final class Gfl2SomberCoverage {
    // The official 0.1.7 Somber post has no sampler on unit 4.
    private static final int UNIT = 4;
    private static int activeProgram, enabledLocation, previousTexture, previousSampler;

    public static void bind(FrameContext frame) {
        if (!Gfl2NativePackSelection.somberActorPost()) return;
        int texture = Gfl2NativeRuntime.somberCoverageTexture(frame.index);
        if (texture == 0) return;
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int enabled = GL20.glGetUniformLocation(program, "gf2HasCoverage");
        if (enabled < 0) return;
        int sampler = GL20.glGetUniformLocation(program, "gf2OriginalNormals");
        if (sampler < 0) return;
        previousTexture = GL30.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, UNIT);
        previousSampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, UNIT);
        GL45.glBindTextureUnit(UNIT, texture);
        GL33.glBindSampler(UNIT, 0);
        GL20.glUniform1i(sampler, UNIT);
        GL20.glUniform1i(enabled, 1);
        activeProgram = program;
        enabledLocation = enabled;
    }
    public static void release() {
        if (activeProgram == 0) return;
        GL41.glProgramUniform1i(activeProgram, enabledLocation, 0);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL33.glBindSampler(UNIT, previousSampler);
        GL13.glActiveTexture(previousUnit);
        activeProgram = 0;
    }
}
