package viewpoint.render;

import gfl2.runtime.EfzAimCompatibility;
import viewpoint.platform.IrisPacks;

/** EFZ's near-band replay bypasses the host's normal Native and Iris model filters. */
public final class Gfl2StockModelReplay {
    static ModelBatches.Filter filter(ModelDraws draws, ModelBatches.Filter original) {
        if (!EfzAimCompatibility.isReplay(original)) return original;
        if (IrisPacks.active() == null) {
            return index -> original.take(index) && !Gfl2NativeRegistry.owns(draws, index);
        }
        var pipeline = Gfl2EclipseSourceHost.pipeline;
        var models = Gfl2EclipseSourceHost.models;
        if (pipeline == null || models == null || draws != models.draws()) return original;
        var state = Gfl2Services.sourceState(pipeline);
        return state == null ? original : index -> original.take(index) && !state.owns(index);
    }

    private Gfl2StockModelReplay() {}
}
