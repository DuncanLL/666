package viewpoint.render;

/** Native entry for the source-vertex adapter. The name is retained for existing tools. */
public final class AimohGf2Rc2Vertex {
    public record Stages(String vertex,String fragment){}
    public static String adapt(String source){return AimohGf2Vertex.adapt(source);}
    public static Stages stages(String source,String fragment){
        var stages=AimohGf2Vertex.bindStages(adapt(source),fragment);
        return new Stages(stages.vertex(),stages.fragment());
    }
}
