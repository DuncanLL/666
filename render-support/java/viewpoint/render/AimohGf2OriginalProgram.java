package viewpoint.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;
import viewpoint.platform.Gl;

/** Original GF2 program/resource interface. Contains no character lighting presets.
 * Registers 0..2 are PS; registers 3..5 are VS. Callers provide actual frame,
 * object and material bytes. A missing active register is an error, not zero light.
 */
public final class AimohGf2OriginalProgram implements AutoCloseable {
    public final GlProgram gl;
    public final String vertexHash,fragmentHash;
    private final int[] sizes=new int[6];
    private final Map<Integer,Integer> textureUnits=new LinkedHashMap<>();
    private int exposureOffsetLocation=-1,lastExposureOffset=-1;
    private int sunVisibilityLocation=-1;
    private static final Pattern TEXTURE=Pattern.compile("(?:hlslcc_zcmp)?GF2(VS)?Texture(\\d+)(?:TEX_with_SMPGF2(?:VS)?Sampler(\\d+))?");

    public AimohGf2OriginalProgram(Path vertex,Path fragment,String vertexHash,String fragmentHash) throws IOException {
        this(Files.readString(vertex),Files.readString(fragment),vertexHash,fragmentHash);
    }
    /** PackSource supplies already verified UTF-8 programs; no runtime files. */
    public AimohGf2OriginalProgram(String vertex,String fragment,String vertexHash,String fragmentHash) {
        var stages=AimohGf2Rc2Vertex.adapt(vertex,fragment);vertex=stages.vertex();fragment=stages.fragment();
        this.vertexHash=vertexHash;this.fragmentHash=fragmentHash;
        gl=GlProgram.fromSources("GF2 original "+vertexHash.substring(0,12)+"/"+fragmentHash.substring(0,12),
            new GlProgram.Sources(vertex,null,null,null,fragment),Map.of(),Map.of(),List.of());
        int previous=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            gl.use();int maxBytes=GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE);
            gl.sampler("uPalettes",31);gl.sampler("uDraws",40);gl.sampler("gf2ModelAttributes",126);
            gl.sampler("gf2AuthoredExposure",95);exposureOffsetLocation=GL20.glGetUniformLocation(gl.id(),"gf2AuthoredExposureOffset");
            gl.sampler("gf2NativeSunVisibility",94);sunVisibilityLocation=GL20.glGetUniformLocation(gl.id(),"gf2NativeSunVisibility");
            gl.sampler("uRelief",Gl.UNIT_FAR_RELIEF);gl.sampler("uReliefWater",Gl.UNIT_FAR_RELIEF_WATER);

            for(int slot=0;slot<6;slot++){
                String name=slot<3?"GF2Buffer"+slot:"GF2VSBuffer"+(slot-3);
                int block=GL31.glGetUniformBlockIndex(gl.id(),name);
                if(block==GL31.GL_INVALID_INDEX)continue;
                sizes[slot]=GL31.glGetActiveUniformBlocki(gl.id(),block,GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
                if(sizes[slot]<=0||sizes[slot]>maxBytes)throw new IllegalStateException("GF2 original UBO exceeds host capability: "+name+" "+sizes[slot]);
                GL31.glUniformBlockBinding(gl.id(),block,8+slot);
            }
            int count=GL20.glGetProgrami(gl.id(),GL20.GL_ACTIVE_UNIFORMS),limit=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            for(int i=0;i<count;i++){
                String name=GL20.glGetActiveUniform(gl.id(),i,256,BufferUtils.createIntBuffer(1),BufferUtils.createIntBuffer(1));
                Matcher matcher=TEXTURE.matcher(name);if(!matcher.matches())continue;
                int location=GL20.glGetUniformLocation(gl.id(),name);if(location<0)continue;
                boolean vertexStage=matcher.group(1)!=null;int originalRegister=Integer.parseInt(matcher.group(2));
                int originalSampler=matcher.group(3)==null?255:Integer.parseInt(matcher.group(3));
                int key=(vertexStage?65536:0)+(originalRegister<<8)+originalSampler;
                // Native model palettes/draw records remain at 31/40. These
                // source samplers occupy the existing high user-unit range.
                int unit=112+textureUnits.size();
                if(unit>=limit)throw new IllegalStateException("GF2 sampler unit exceeds host capability: "+unit);
                textureUnits.put(key,unit);GL20.glUniform1i(location,unit);
            }
        } catch(RuntimeException e){close();throw e;}
        finally {GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,0);GL20.glUseProgram(previous);}
    }

    public int registerBytes(int slot){return sizes[slot];}
    public void nativeSunVisibility(int texture){
        if(sunVisibilityLocation<0)return;
        if(texture<=0)throw new IllegalArgumentException("Native sun visibility producer absent");
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+94);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(94,0);GL13.glActiveTexture(active);
    }
    public void authoredExposureView(int texture,int offset){
        if(exposureOffsetLocation<0)return;
        if(offset!=lastExposureOffset){GL41.glProgramUniform1i(gl.id(),exposureOffsetLocation,offset);lastExposureOffset=offset;}
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+95);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL33.glBindSampler(95,0);GL13.glActiveTexture(active);
    }
    GlProgram nativeProgram(){return gl;}
    public void nativeInputs(float[] inverse,float[] inverseNormal,int count){
        gl.use();gl.set("gf2WorldToSource",new org.joml.Matrix4f().set(inverse));
        gl.set("gf2WorldNormalToSource",new org.joml.Matrix3f().set(inverseNormal));
        gl.setInt("gf2AttributeCount",count);gl.setInt("gf2AttributeOffset",0);
    }
    public void nativeFrame(float[] previousModel,float[] previousInverse,float[] eye,float[] jitter,boolean history){
        gl.use();
        gl.set("gf2PreviousModel",new org.joml.Matrix4f().set(previousModel));
        gl.set("gf2PreviousWorldToSource",new org.joml.Matrix4f().set(previousInverse));
        gl.set("gf2PreviousEye",eye[0],eye[1],eye[2]);gl.set("gf2ClipJitter",jitter[0],jitter[1]);
        gl.setInt("gf2HistoryEnabled",history?1:0);
    }
    public void renderScale(float value){gl.set("gf2ClipScale",value);}
    private int[] cachedSourceOutputs;
    public int[] sourceOutputs(){
        if(cachedSourceOutputs!=null)return cachedSourceOutputs;
        int[] outputs=new int[3];Arrays.fill(outputs,GL11.GL_NONE);
        for(int i=0;i<3;i++)if(GL30.glGetFragDataLocation(gl.id(),"SV_Target"+i)>=0)outputs[i]=GL30.GL_COLOR_ATTACHMENT0+i;
        return cachedSourceOutputs=outputs;
    }
    public Set<Integer> textureRegisters(){return Collections.unmodifiableSet(textureUnits.keySet());}

    /** Bind caller-owned cached UBOs. Frame data is uploaded once per frame;
     * material data once per material load, never rebuilt for each draw/program. */
    public void registers(Map<Integer,Integer> data){
        gl.use();
        for(int slot=0;slot<6;slot++)if(sizes[slot]>0){
            Integer buffer=data.get(slot);
            if(buffer==null||buffer<=0)throw new IllegalArgumentException("Missing GF2 register bank "+slot+"; need "+sizes[slot]+" bytes");
            GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER,8+slot,buffer,0,sizes[slot]);
        }
    }

    /** Key=(VS?65536:0)|(tRegister<<8)|sRegister; 255 means uncombined.
     * The same texture sampled with two source samplers gets separate GL units.
     * Defaults must be explicit resources, including the source inline sampler. */
    public void textures(Map<Integer,int[]> data){
        for(var entry:textureUnits.entrySet()){
            int[] value=data.get(entry.getKey());
            if(value==null||value.length!=3||value[1]<=0)throw new IllegalArgumentException("Missing GF2 source texture register "+entry.getKey());
            GL13.glActiveTexture(GL13.GL_TEXTURE0+entry.getValue());GL11.glBindTexture(value[0],value[1]);GL33.glBindSampler(entry.getValue(),value[2]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static double numeric(JSONObject state,String name){
        Object value=state.get(name);if(value instanceof Number n)return n.doubleValue();
        if(value instanceof JSONObject object&&object.has("value"))return object.getDouble("value");
        throw new IllegalArgumentException("GF2 render-state input unresolved: "+name);
    }
    private static int number(JSONObject state,String name){return (int)numeric(state,name);}
    private static int compare(int value){return switch(value){case 0,8->GL11.GL_ALWAYS;case 1->GL11.GL_NEVER;case 2->GL11.GL_LESS;case 3->GL11.GL_EQUAL;case 4->GL11.GL_LEQUAL;case 5->GL11.GL_GREATER;case 6->GL11.GL_NOTEQUAL;case 7->GL11.GL_GEQUAL;default->throw new IllegalArgumentException("GF2 comparison "+value);};}
    private static int stencil(int value){return switch(value){case 0->GL11.GL_KEEP;case 1->GL11.GL_ZERO;case 2->GL11.GL_REPLACE;case 3->GL11.GL_INCR;case 4->GL11.GL_DECR;case 5->GL11.GL_INVERT;case 6->GL14.GL_INCR_WRAP;case 7->GL14.GL_DECR_WRAP;default->throw new IllegalArgumentException("GF2 stencil op "+value);};}
    private static int blend(int value){return switch(value){case 0->GL11.GL_ZERO;case 1->GL11.GL_ONE;case 2->GL11.GL_DST_COLOR;case 3->GL11.GL_SRC_COLOR;case 4->GL11.GL_ONE_MINUS_DST_COLOR;case 5->GL11.GL_SRC_ALPHA;case 6->GL11.GL_ONE_MINUS_SRC_COLOR;case 7->GL11.GL_DST_ALPHA;case 8->GL11.GL_ONE_MINUS_DST_ALPHA;case 9->GL11.GL_SRC_ALPHA_SATURATE;case 10->GL11.GL_ONE_MINUS_SRC_ALPHA;default->throw new IllegalArgumentException("GF2 blend factor "+value);};}
    private static int equation(int value){return switch(value){case 0->GL14.GL_FUNC_ADD;case 1->GL14.GL_FUNC_SUBTRACT;case 2->GL14.GL_FUNC_REVERSE_SUBTRACT;case 3->GL14.GL_MIN;case 4->GL14.GL_MAX;default->throw new IllegalArgumentException("GF2 blend op "+value);};}

    /** Original ShaderLab state. Caller owns FBO, front winding and restoration. */
    public static void state(JSONObject s){
        int depth=number(s,"zTest");if(depth==0)GL11.glDisable(GL11.GL_DEPTH_TEST);else {GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(compare(depth));}
        GL11.glDepthMask(number(s,"zWrite")!=0);
        int cull=number(s,"culling");if(cull==0)GL11.glDisable(GL11.GL_CULL_FACE);else {GL11.glEnable(GL11.GL_CULL_FACE);GL11.glCullFace(cull==1?GL11.GL_FRONT:GL11.GL_BACK);}
        JSONObject b=s.getJSONObject("rtBlend0");int mask=number(b,"colMask");GL11.glColorMask((mask&8)!=0,(mask&4)!=0,(mask&2)!=0,(mask&1)!=0);
        int src=number(b,"srcBlend"),dst=number(b,"destBlend"),srcA=number(b,"srcBlendAlpha"),dstA=number(b,"destBlendAlpha");
        if(src==1&&dst==0&&srcA==1&&dstA==0)GL11.glDisable(GL11.GL_BLEND);
        else {GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(blend(src),blend(dst),blend(srcA),blend(dstA));}
        GL20.glBlendEquationSeparate(equation(number(b,"blendOp")),equation(number(b,"blendOpAlpha")));
        if(s.getBoolean("rtSeparateBlend"))for(int attachment=0;attachment<8;attachment++){
            JSONObject part=s.getJSONObject("rtBlend"+attachment);int channels=number(part,"colMask");
            GL30.glColorMaski(attachment,(channels&8)!=0,(channels&4)!=0,(channels&2)!=0,(channels&1)!=0);
            int a=number(part,"srcBlend"),d=number(part,"destBlend"),aa=number(part,"srcBlendAlpha"),da=number(part,"destBlendAlpha");
            if(a==1&&d==0&&aa==1&&da==0)GL30.glDisablei(GL11.GL_BLEND,attachment);else GL30.glEnablei(GL11.GL_BLEND,attachment);
            GL40.glBlendFuncSeparatei(attachment,blend(a),blend(d),blend(aa),blend(da));
            GL40.glBlendEquationSeparatei(attachment,equation(number(part,"blendOp")),equation(number(part,"blendOpAlpha")));
        }
        JSONObject op=s.getJSONObject("stencilOp");int comp=number(op,"comp");
        // Original RDC: disabled comparison still executes authored stencil writes.
        boolean stencilWrites=number(s,"stencilWriteMask")!=0&&(number(op,"pass")!=0||number(op,"fail")!=0||number(op,"zFail")!=0);
        if(comp==0&&!stencilWrites)GL11.glDisable(GL11.GL_STENCIL_TEST);else {
            GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glStencilFunc(comp==0?GL11.GL_ALWAYS:compare(comp),number(s,"stencilRef"),number(s,"stencilReadMask"));
            GL11.glStencilMask(number(s,"stencilWriteMask"));GL11.glStencilOp(stencil(number(op,"fail")),stencil(number(op,"zFail")),stencil(number(op,"pass")));
        }
        float factor=(float)numeric(s,"offsetFactor"),units=(float)numeric(s,"offsetUnits");
        if(factor==0&&units==0)GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);else {GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);GL11.glPolygonOffset(factor,units);}
        if(number(s,"alphaToMask")==0)GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);else GL11.glEnable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
        if(number(s,"zClip")==0)GL11.glEnable(GL32.GL_DEPTH_CLAMP);else GL11.glDisable(GL32.GL_DEPTH_CLAMP);
    }

    @Override public void close(){if(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==gl.id())GL20.glUseProgram(0);gl.delete();}
}
