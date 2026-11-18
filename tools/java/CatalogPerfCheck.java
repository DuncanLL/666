package viewpoint.render;

import java.util.*;
import org.json.*;
import org.lwjgl.opengl.*;
import viewpoint.platform.*;

/** Exercise the declared shader pairs and material bytes of every loaded pack. */
public final class CatalogPerfCheck {
    public static String run(boolean eclipse) throws Exception {
        viewpoint.iris.SourceFiles source=eclipse ? IrisPacks.drawn().pack().source : Gfl2NativePackSelection::readData;
        var data=new Gfl2PackData(source);
        Map<String,Set<String>> passes=new HashMap<>();
        for(Object v:data.manifest.getJSONArray("programs")){
            JSONObject row=(JSONObject)v;
            passes.computeIfAbsent(row.getString("material"),k->new LinkedHashSet<>()).add(row.getString("pass"));
        }
        Set<Integer> ids=new TreeSet<>();
        for(Object v:data.manifest.getJSONArray("models"))for(Object part:((JSONObject)v).getJSONArray("parts"))
            ids.add(((JSONObject)part).getInt("source_material"));
        Set<Integer> linked=new HashSet<>();
        try(var materials=eclipse?null:new Gfl2PackMaterials(data);
            var iris=eclipse?new Gfl2EclipsePackMaterials(data):null;
            var nativePrograms=eclipse?null:new Gfl2NativeSourcePrograms(data,materials)){
            for(int id:ids)for(String pass:passes.getOrDefault((eclipse?iris.material(id):materials.material(id)).getString("name"),Set.of())){
                var p=eclipse?iris.program(id,pass,false).nativeProgram():nativePrograms.source(id,pass).nativeProgram();
                if(GL20.glGetProgrami(p.id(),GL20.GL_LINK_STATUS)==0)throw new AssertionError("Unlinked "+pass);
                linked.add(p.id());
                if(pass.equals("GFCharForward")){
                    var early=eclipse?iris.program(id,pass,true).nativeProgram():nativePrograms.early(id).nativeProgram();
                    if(GL20.glGetProgrami(early.id(),GL20.GL_LINK_STATUS)==0)throw new AssertionError("Unlinked early pass");
                    linked.add(early.id());
                }
            }
            if(!(eclipse?iris.verifyMaterialUploads():materials.verifyMaterialUploads()))throw new AssertionError("Material bytes differ");
            return new JSONObject().put("models",data.manifest.getJSONArray("models").length())
                .put("unique_linked_pairs",linked.size()).put("material_count",(eclipse?iris.materialCount():materials.materialCount()))
                .put("material_upload_readback_equal",true).toString();
        }
    }
}
