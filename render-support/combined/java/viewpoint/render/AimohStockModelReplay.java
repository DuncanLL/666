package viewpoint.render;

import aimoh.pmxpreview.EfzAimCompatibility;
import viewpoint.platform.IrisPacks;

/** EFZ's near-band replay bypasses the host's normal Native and Iris model filters. */
public final class AimohStockModelReplay {
    static ModelBatches.Filter filter(ModelDraws draws, ModelBatches.Filter original) {
        if (!EfzAimCompatibility.isReplay(original)) return original;
        if (IrisPacks.active() == null) {
            return index -> original.take(index) && !AimohNativeGf2Registry.owns(draws, index);
        }
        var pipeline = AimohEclipseGf2SourceHost.pipeline;
        var models = AimohEclipseGf2SourceHost.models;
        if (pipeline == null || models == null || draws != AimohWorkshopRender.draws(models)) return original;
        var state = AimohGF2Services.sourceState(pipeline);
        return state == null ? original : index -> original.take(index) && !state.owns(index);
    }

    private AimohStockModelReplay() {}
}
