package aimoh.pmxpreview;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.json.JSONObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;

/** Isolated calibration pose and a close-up of the held item. */
final class GripRoomPreview {
    private static final IdentityHashMap<IsoGameCharacter,GripRoomPreview> active=new IdentityHashMap<>();
    private static final Field HANDLE=field(VertexBufferObject.class,"handle");
    private static final Field FORMAT=field(VertexBufferObject.class,"vertexFormat");
    private static final Field ELEMENTS=field(FORMAT.getType(),"elements");

    record Part(ModelMesh mesh,Texture texture,Matrix4f placement) {}
    record View(Vector3f center,List<Part> parts) {}

    private final ModelSession equipped,session;
    private final AnimationPlayer[] poses=new AnimationPlayer[2];
    private final String[] poseClips=new String[2];
    private Model poseModel;
    private InventoryItem lastItem;
    private ModelInstance lastHeld;
    private KahluaTable lastCalibration;
    private final long poseTime=System.nanoTime();
    private long appearanceRevision=Long.MIN_VALUE,frame;
    private AnimationPlayer lastPose;
    private boolean lastAim;
    private View view;
    private Vector3f viewAnchor;

    private GripRoomPreview(ModelSession equipped,ModelSession session) {
        this.equipped=equipped;
        this.session=session;
    }

    static boolean draw(IsoGameCharacter actor,int x,int y,int width,int height,boolean aiming,float yaw,float pitch,float zoom) {
        ModelSession equipped=Main.session(actor);
        AnimationPlayer live=actor.getAnimationPlayer();
        if(equipped==null||equipped.failed||equipped.retired||live==null||!live.isReady()||width<16||height<16)return false;
        GripRoomPreview preview=active.get(actor);
        try {
            if(preview==null||preview.equipped!=equipped){
                release(actor);
                preview=new GripRoomPreview(equipped,Main.createRoomPreview(equipped.entry.id()));
                active.put(actor,preview);
            }
            preview.update(actor,live,aiming);
            ModelSession.Snapshot snapshot=preview.session.snapshot();
            if(preview.view==null||snapshot==null||!preview.session.ready||preview.session.failed)return false;
            var mesh=preview.session.plainMesh(snapshot.frame());
            SpriteRenderer.instance.drawGeneric(new ReadyRoomPreview.Draw(mesh,snapshot.frame(),snapshot.materials(),x,y,width,height,Core.height,yaw,zoom,0,0,false,preview.view,pitch));
            return true;
        }catch(Exception|LinkageError failure){
            if(preview!=null&&!preview.session.failed)preview.session.fail(failure);
            return false;
        }
    }

    static void release(IsoGameCharacter actor) {
        GripRoomPreview preview=active.remove(actor);
        if(preview!=null){
            try{preview.clearPoses();}
            finally{preview.session.retire();}
        }
    }

    private void update(IsoGameCharacter actor,AnimationPlayer live,boolean aiming) {
        if(session.failed)return;
        InventoryItem item=GripCalibration.heldItem(actor);
        int side=GripCalibration.heldSide(actor);
        ModelInstance held=side==1?actor.primaryHandModel:side==0?actor.secondaryHandModel:null;
        if(held!=null&&(held.model==null||!held.model.isStatic||!held.model.mesh.isReady()))held=null;
        aiming=aiming&&item!=null;
        AnimationPlayer pose=basePose(live.getModel(),poseClip(actor,item,aiming),aiming);
        if(pose==null)return;
        long revision=equipped.model.appearance().revision();
        KahluaTable calibration=item==null?null:GripCalibration.currentRow(actor,session.entry.id(),item,aiming);
        if(view!=null&&item==lastItem&&held==lastHeld&&pose==lastPose&&aiming==lastAim&&revision==appearanceRevision&&calibration==lastCalibration)return;
        if(revision!=appearanceRevision) {
            var appearance=new JSONObject(equipped.model.appearance().stateJson());
            session.applySettings(new JSONObject().put("appearance",appearance)
                .put("appearance_controls",equipped.profile.optJSONObject("appearance_controls")),false);
            session.model.appearance().loadExpression(equipped.model.appearance().expressionSnapshot());
        }

        Matrix4f itemMatrix=item==null?null:held==null?GripCalibration.handPlacement(pose,side):localPlacement(held,pose,new Matrix4f());
        var adjustment=calibration==null?null:GripCalibration.previewRequest(actor,item,itemMatrix,calibration);
        var geometry=session.update(frame++,poseTime,pose,item!=null,0,0,0,false,false,false,adjustment,false);
        var parts=new ArrayList<Part>();
        if(held!=null)collect(held,pose,itemMatrix,held,side,item.getType(),parts);
        var low=new Vector3f(Float.POSITIVE_INFINITY);var high=new Vector3f(Float.NEGATIVE_INFINITY);
        geometry.includeBounds(low,high);
        if(!low.isFinite()||!high.isFinite()||high.y<=low.y){
            var mesh=session.plainMesh(session.snapshot().frame());
            low.set(mesh.centerX,mesh.centerY-mesh.span/2.4f,mesh.centerZ);
            high.set(mesh.centerX,mesh.centerY+mesh.span/2.4f,mesh.centerZ);
        }
        if(viewAnchor==null||pose!=lastPose||item!=lastItem||held!=lastHeld||revision!=appearanceRevision){
            viewAnchor=new Vector3f(low).lerp(high,.5f);
            viewAnchor.y=low.y+(high.y-low.y)*(aiming?.74f:.56f);
            if(!parts.isEmpty()){
                var part=parts.getFirst();
                var box=new Vector3f(part.mesh.minXyz).lerp(part.mesh.maxXyz,.5f);
                var itemCenter=part.placement.transformPosition(box);
                if(itemCenter.isFinite())viewAnchor.lerp(itemCenter,.45f);
            }
        }
        view=new View(viewAnchor,List.copyOf(parts));
        lastItem=item;lastHeld=held;lastPose=pose;lastAim=aiming;lastCalibration=calibration;appearanceRevision=revision;
    }

    static String poseClip(IsoGameCharacter actor,InventoryItem item,boolean aiming) {
        WeaponType type=WeaponType.UNARMED;
        if(item instanceof HandWeapon weapon&&item==actor.getPrimaryHandItem()){
            type=WeaponType.getWeaponType(weapon);
            if(item!=actor.getSecondaryHandItem())type=switch(type){
                case FIREARM -> WeaponType.HANDGUN;
                case TWO_HANDED,SPEAR,CHAINSAW -> WeaponType.ONE_HANDED;
                default -> type;
            };
        }
        return switch(type){
            case FIREARM -> aiming?"Bob_IdleAimRifle":"Bob_IdleNoAimRifle";
            case HANDGUN -> aiming?"Bob_IdleAimHandgun":"Bob_IdleHandgun";
            case TWO_HANDED -> aiming?"Bob_IdleAimBat":"Bob_IdleBat";
            case SPEAR -> aiming?"Bob_IdleAimSpear":"Bob_IdleBat";
            case HEAVY -> aiming?"Bob_IdleAim2H_Heavy":"Bob_Idle2H_Heavy";
            case CHAINSAW -> aiming?"Bob_IdleAimChainsaw":"Bob_IdleChainsaw";
            default -> aiming?"Bob_IdleAim1Hand":"Bob_Idle";
        };
    }

    private AnimationPlayer basePose(Model model,String clip,boolean aiming) {
        if(poseModel!=model){clearPoses();poseModel=model;}
        int index=aiming?1:0;
        AnimationPlayer previous=poses[index];
        if(previous!=null&&clip.equals(poseClips[index]))return previous;
        AnimationPlayer pose=AnimationPlayer.alloc(model);
        try {
            var track=pose.play(clip,true);
            if(track==null)track=pose.play("Bob_Idle",true);
            if(track==null)return null;
            track.setBlendWeight(1);
            track.setCurrentTimeValue(track.getDuration()*.25f);
            pose.Update(0);
            if(previous!=null)previous.release();
            poses[index]=pose;
            poseClips[index]=clip;
            return pose;
        }finally{if(poses[index]!=pose)pose.release();}
    }

    private void clearPoses() {
        for(int i=0;i<poses.length;i++)if(poses[i]!=null){poses[i].release();poses[i]=null;}
        lastPose=null;
    }

    private static Matrix4f localPlacement(ModelInstance part,AnimationPlayer pose,Matrix4f parent) {
        var transform=new Matrix4f(parent);
        var parentModel=part.parent;
        var mount=parentModel==null?null:parentModel.getAttachmentById(part.attachmentNameParent);
        if(mount==null&&parentModel!=null&&part.parentBoneName!=null)mount=parentModel.getAttachmentById(part.parentBoneName);
        String bone=mount==null?part.parentBoneName:mount.getBone();
        if(bone!=null){
            var boneTransform=new Matrix4f();
            ModelInstanceRenderData.makeBoneTransform(pose,bone,boneTransform);
            boneTransform.mul(transform,transform);
        }
        if(mount!=null)transform.mul(ModelInstanceRenderData.makeAttachmentTransform(mount,new Matrix4f()));
        var self=part.getAttachmentById(part.attachmentNameSelf);
        if(self==null&&part.parentBoneName!=null)self=part.getAttachmentById(part.parentBoneName);
        if(self!=null){
            var local=ModelInstanceRenderData.makeAttachmentTransform(self,new Matrix4f());
            if(ModelInstanceRenderData.invertAttachmentSelfTransform)local.invert();
            transform.mul(local);
        }
        ModelInstanceRenderData.postMultiplyMeshTransform(transform,part.model.mesh);
        return transform.scale(part.scale);
    }

    private void collect(ModelInstance part,AnimationPlayer pose,Matrix4f local,ModelInstance root,int side,String key,List<Part> output) {
        if(part.model==null||!part.model.isStatic||!part.model.mesh.isReady())return;
        Texture texture=part.tex==null?part.model.tex:part.tex;
        Matrix4f mapped=session.attachment(root,side,key,local,pose);
        if(texture!=null&&mapped.isFinite())output.add(new Part(part.model.mesh,texture,mapped));
        for(var child:part.sub)if(child.model!=null)collect(child,pose,localPlacement(child,pose,local),root,side,key,output);
    }

    static void paintItems(List<Part> parts,int vao,int turnUniform,Matrix4f turn) throws IllegalAccessException {
        GL30.glBindVertexArray(vao);
        for(var part:parts) {
            var vb=part.mesh.vb;
            if(vb==null||!(HANDLE.get(vb) instanceof VertexBufferObject.Vbo vbo)||vbo.vboId<=0||vbo.eboId<=0||!part.texture.isReady())continue;
            var elements=(VertexBufferObject.VertexElement[])ELEMENTS.get(FORMAT.get(vb));
            int position=-1,normal=-1,uv=-1;
            for(var element:elements)if(element!=null)switch(element.type){
                case VertexArray -> position=element.byteOffset;
                case NormalArray -> normal=element.byteOffset;
                case TextureCoordArray -> {if(uv<0)uv=element.byteOffset;}
                default -> {}
            }
            if(position<0||normal<0||uv<0)continue;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo.vboId);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,vbo.eboId);
            for(int i=0;i<3;i++)GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,vbo.vertexStride,(long)position);
            GL20.glVertexAttribPointer(1,3,GL11.GL_FLOAT,false,vbo.vertexStride,(long)normal);
            GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,vbo.vertexStride,(long)uv);
            GL20.glUniformMatrix4fv(turnUniform,false,new Matrix4f(turn).mul(part.placement).get(new float[16]));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,part.texture.getID());
            GL11.glDrawElements(GL11.GL_TRIANGLES,vbo.numElements,GL11.GL_UNSIGNED_INT,0L);
        }
    }

    private static Field field(Class<?> owner,String name) {
        try {
            Field field=owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
}
