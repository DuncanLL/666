package gfl2.runtime;

import me.zed_0xff.zombie_buddy.Exposer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.scripting.ScriptManager;

@Exposer.LuaClass
public final class GFL2ReadyRoom extends ReadyRoomPreview {
    public static boolean centerWeaponPreview(String id,Vector3f translation) {
        var script=ScriptManager.instance.getModelScript(id);
        var model=ModelManager.instance.getLoadedModel(id);
        if(script==null||model==null||model.mesh==null||!model.mesh.isReady())return false;
        var mesh=model.mesh;
        // Match UI3DScene's mesh transform and script scale, including for painted variants.
        var transform=ModelInstanceRenderData.postMultiplyMeshTransform(new Matrix4f(),mesh);
        translation.set(mesh.minXyz).lerp(mesh.maxXyz,.5f).mul(script.scale);
        transform.transformPosition(translation).negate();
        return true;
    }
}
