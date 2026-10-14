package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Reproducible dry-block technique lanes. Geometry is shared by the map and its rehearsal tests. */
public final class ParkourSkills {
    public static final class Skill {
        public final String id,label;
        public final List<int[]> boxes=new ArrayList<>();
        public final List<PathSearch.Pos> targets=new ArrayList<>();
        public double startX=.5;
        public int strafe,lead=-1;
        Skill(String id,String label) {this.id=id;this.label=label;}
        Skill box(int x0,int y0,int z0,int x1,int y1,int z1) {boxes.add(new int[]{x0,y0,z0,x1,y1,z1});return this;}
        Skill floor(int x0,int x1,int y,int z) {return box(x0,y,z,x1,y,z);}
        Skill goal(int x,int y,int z) {targets.add(new PathSearch.Pos(x,y,z));return this;}
        public State start() {return new State(startX,1,4.5,0,-.0784,0,true);}
        public List<Solid> geometry() {List<Solid> out=new ArrayList<>();for(int[] b:boxes)out.add(new Solid(b[0],b[1],b[2],b[3]+1,b[4]+1,b[5]+1));return out;}
    }
    public static final List<Skill> ALL=Collections.unmodifiableList(create());
    private static Skill gap(String id,String label,int z,int y,int landing) {
        return new Skill(id,label).floor(0,7,0,4).floor(landing,landing+6,y,z).goal(landing+2,y+1,z);
    }
    private static List<Skill> create() {
        List<Skill> a=new ArrayList<>();
        Skill left=gap("strafe-left","45 strafe W+A",4,0,12);left.strafe=1;a.add(left);
        Skill right=gap("strafe-right","45 strafe W+D",4,0,12);right.strafe=-1;a.add(right);
        Skill switching=gap("strafe-switch","Alternating 45",4,0,12);switching.strafe=2;a.add(switching);
        a.add(gap("diagonal-left","Diagonal left",2,0,11));
        a.add(gap("diagonal-right","Diagonal right",6,0,11));
        Skill hh=gap("headhitter","2-block ceiling",4,0,10).box(0,3,4,6,3,4);hh.lead=6;a.add(hh);
        a.add(gap("low-ceiling","3-block ceiling",4,0,11).box(0,4,4,17,4,4));
        for(int delay=0;delay<2;delay++) {
            Skill s=new Skill(delay==0?"jam":"delayed","Jump timing "+delay+"t").floor(0,2,0,4).floor(4,10,0,4).goal(6,1,4);
            s.startX=2.0;s.lead=delay;a.add(s);
        }
        a.add(new Skill("momentum-chain","Linked jumps").floor(0,5,0,4).floor(9,12,0,4).floor(16,22,0,4).goal(10,1,4).goal(18,1,4));
        a.add(new Skill("precision","1-block landing").floor(0,7,0,4).floor(11,11,0,4).goal(11,1,4));
        a.add(gap("up-one","Jump up one",4,1,10));
        a.add(gap("drop-two","Jump down two",4,-2,13));
        for(int thick=1;thick<=2;thick++)a.add(new Skill(thick==1?"single-neo":"double-neo",thick+"-block neo")
                .floor(0,22,0,4).box(10,1,4,9+thick,3,4).goal(14+thick,1,4));
        a.add(new Skill("zigzag","Zigzag jumps").floor(0,5,0,4).floor(8,11,0,6).floor(14,17,0,3).floor(20,24,0,6)
                .goal(9,1,6).goal(15,1,3).goal(22,1,6));
        return a;
    }
    public static Skill find(String id) {for(Skill s:ALL)if(s.id.equalsIgnoreCase(id))return s;throw new IllegalArgumentException("Unknown skill: "+id+". Choices: "+names());}
    public static String names() {List<String> names=new ArrayList<>();for(Skill s:ALL)names.add(s.id);return String.join(", ",names);}
    public static ParkourCoursePlan.Tape rehearse(Skill skill,State start,List<Solid> geometry,int x,int y,int z) {
        ParkourCoursePlan.Tape tape=new ParkourCoursePlan.Tape(start);
        // Jam and delayed timing are literal key sequences. Headhitter starts with repeated ceiling jumps.
        if(skill.lead>=0)for(int tick=0;tick<=skill.lead;tick++) {
            Input input=new Input(-90,true,skill.lead==6 ? tape.end().ground : tick==skill.lead);
            State state=step(tape.end(),input,geometry);tape.frames.add(new ParkourCoursePlan.Frame(input,8,state,null));
        }
        for(PathSearch.Pos p:skill.targets) {
            Goal goal=ParkourCoursePlan.goal(new PathSearch.Pos(x+p.x,y+p.y,z+p.z));
            Plan plan=TerrainParkourPlanner.plan(tape.end(),goal,geometry,skill.strafe);
            if(plan==null)throw new IllegalStateException("No verified trajectory for "+skill.id);
            for(int i=0;i<plan.inputs.size();i++)tape.frames.add(new ParkourCoursePlan.Frame(plan.inputs.get(i),8,plan.states.get(i),null));
            tape.checkpoints.add(tape.frames.size());
        }
        return tape;
    }
}
