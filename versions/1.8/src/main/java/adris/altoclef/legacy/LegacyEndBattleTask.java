package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.entity.*;
import net.minecraft.entity.boss.dragon.*;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;

/** Crystal-head timing practice using real pearls, cover placement and ordinary melee packets. */
final class LegacyEndBattleTask implements LegacyTask {
    private final boolean oneCrystal;
    private final boolean towerTopDrill;
    private EndCrystalEntity crystal;
    private EnderDragonEntity dragon;
    private LegacyTask child;
    private int ticks,stage,stageSince,nextPlan,nextVantagePlan,shots,blasts,landingTicks,stableAfterBlast;
    private int lastChargeTargetTick=-1000;
    private int dx,dz;
    private BlockPos landing,cover,stance;
    private boolean retreating;
    private boolean stanceChosen;
    private int lastSwordHitTick=-1000;
    private int towerBowStarted,towerBowReleasedAt,towerBowAttempts;
    private int lastEndermanHitTick=-1000;
    private UUID defendingEnderman;
    private PathSearch.Pos arenaGoal;
    private EndCrystalEntity arrowTarget;
    private CrystalArrowTrajectory.Shot arrowShot;
    private final Set<UUID> rejectedArrows=new HashSet<>();
    private final Map<UUID,Integer> missedArrows=new HashMap<>();
    private Vec3d lastBowOrigin;
    private PathSearch.Pos bowVantage;
    private int bowDrawStarted,bowReleasedAt;
    private PathSearch.Pos dodgeGoal;
    private int dodgeUntil,lastDodgeTick=-1000;
    private boolean baitPlaced,baitPerched,retreated;
    private final Deque<PathSearch.Pos> topRoute=new ArrayDeque<>();
    private BlockPos routeGoal;
    private PearlTrajectory.Shot shot;
    private final Set<UUID> used=new HashSet<>();
    private float beforeHit,damage;
    private float healthAtHit;
    private double exposureAtHit;
    private boolean landed;
    private boolean returningToGround;
    private Vec3d lastHead;
    private DragonChargeTracker.Snapshot charge;
    private boolean wasTargeted;
    private double headVX,headVY,headVZ;
    private String status="locate dragon and intact pillar crystals";
    private boolean supported(LegacyBot bot) {
        PathSearch.Pos p=bot.navigation.feet();
        return bot.mc.player.onGround || Math.abs(bot.mc.player.y-p.y)<.025 && bot.navigation.floor(p.x,p.y-1,p.z);
    }
    private boolean movingEntitiesClear(LegacyBot bot) {
        for(Object o:bot.mc.world.loadedEntities) if(o instanceof LivingEntity && o!=bot.mc.player && !((Entity)o).removed) {
            Entity e=(Entity)o;
            Box box=e.getBoundingBox().expand(e instanceof EnderDragonEntity ? 6 : .5,e instanceof EnderDragonEntity ? 4 : .5,e instanceof EnderDragonEntity ? 6 : .5);
            for(int i=1;i<shot.points.size();i++) {
                double[] a=shot.points.get(i-1),b=shot.points.get(i);
                Vec3d from=new Vec3d(a[0],a[1],a[2]),to=new Vec3d(b[0],b[1],b[2]);
                if(box.contains(from) || box.method_585(from,to)!=null) return false;
            }
        }
        return true;
    }
    LegacyEndBattleTask(boolean oneCrystal) {this(oneCrystal,false);}
    LegacyEndBattleTask(boolean oneCrystal,boolean towerTopDrill) {
        this.oneCrystal=oneCrystal;this.towerTopDrill=towerTopDrill;
    }
    int pearlThrows() {return shots;}
    int detonations() {return blasts;}
    float dealtDamage() {return damage;}
    double exposureAtHit() {return exposureAtHit;}
    boolean landedWithCrystalIntact() {return landed;}
    boolean baitPlaced() {return baitPlaced;}
    boolean baitPerched() {return baitPerched;}
    boolean retreated() {return retreated;}
    public String describe() {return "End crystal fight -> "+status+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.useKey,false);bot.navigation.reset();}
    private void stage(int value,LegacyBot bot) {
        stage=value;stageSince=bot.ticks;bot.navigation.reset();topRoute.clear();routeGoal=null;
        if(value==3) {towerBowStarted=0;towerBowReleasedAt=0;towerBowAttempts=0;}
        if(value<3 || value>5) lastChargeTargetTick=-1000;
    }
    private boolean stableForPearl(LegacyBot bot) {
        PathSearch.Pos p=bot.navigation.feet();
        double vx=bot.mc.player.velocityX,vz=bot.mc.player.velocityZ;
        return bot.mc.player.onGround && bot.navigation.floor(p.x,p.y-1,p.z)
                && vx*vx+vz*vz<.08*.08 && Math.abs(bot.mc.player.velocityY)<.08;
    }
    private String block(LegacyBot bot) {
        for(String id:new String[]{"obsidian","cobblestone","end_stone","dirt"}) if(bot.count(LegacyItems.parse(id))>0) return id;
        throw new TaskFailure("Carry solid blocks for crystal cover and tower footing.");
    }
    private boolean crystalAlive() {return crystal!=null && !crystal.removed;}
    private boolean clearingEarlyCrystals(LegacyBot bot) {return !oneCrystal && !crystals(bot).isEmpty();}
    private List<EndCrystalEntity> crystals(LegacyBot bot) {
        List<EndCrystalEntity> list=new ArrayList<>();
        for(Object e:bot.mc.world.loadedEntities) if(e instanceof EndCrystalEntity && !((Entity)e).removed && !used.contains(((Entity)e).getUuid())) list.add((EndCrystalEntity)e);
        // The dragon periodically attaches to a healing crystal. Prefer that
        // real server-side choice; otherwise approach the crystals near its
        // current flight path instead of always picking the player's nearest.
        list.sort(Comparator.comparingDouble(e-> {
            if(!oneCrystal) return bot.mc.player.squaredDistanceTo(e);
            if(charge!=null && e.getEntityId()==charge.healingCrystal) return -1e9;
            double x=(charge==null ? dragon.partHead.x : charge.x+charge.vx*12)-e.x;
            double y=(charge==null ? dragon.partHead.y : charge.y+charge.vy*12)-e.y;
            double z=(charge==null ? dragon.partHead.z : charge.z+charge.vz*12)-e.z;
            return x*x+y*y+z*z+bot.mc.player.squaredDistanceTo(e)*.15;
        }));return list;
    }
    private boolean activeCharge(LegacyBot bot) {return bot.ticks-lastChargeTargetTick<=20;}
    private boolean healingFromCurrentCrystal() {
        return crystalAlive() && charge!=null && charge.healingCrystal==crystal.getEntityId();
    }
    private boolean burstOnCharge(LegacyBot bot) {
        boolean charging=activeCharge(bot),healing=healingFromCurrentCrystal();
        if(stage<3 || stage>5 || !crystalAlive() || clearingEarlyCrystals(bot) || !(charging || healing)
                || bot.mc.player.squaredDistanceTo(crystal)>16) return false;
        double hx=(charge==null ? dragon.partHead.x : charge.x)-crystal.x;
        double hy=(charge==null ? dragon.partHead.y : charge.y)-crystal.y;
        double hz=(charge==null ? dragon.partHead.z : charge.z)-crystal.z;
        // Wait for the closest sampled part of the charge. Fire immediately
        // once the head is very close, or when the next server-tick position
        // would be farther away. The latter is the last safe chance for a
        // glancing pass: do not hold the crystal until the dragon escapes.
        double now=hx*hx+hy*hy+hz*hz;
        double next=(hx+headVX)*(hx+headVX)+(hy+headVY)*(hy+headVY)+(hz+headVZ)*(hz+headVZ);
        if(now>8*8 || now>4*4 && next<now) return false;
        double currentExposure=bot.mc.world.method_3612(new Vec3d(crystal.x,crystal.y,crystal.z),bot.mc.player.getBoundingBox());
        if(currentExposure>.35) return false;
        if(child!=null) {child.stop(bot);child=null;}
        bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.sneakKey,true);
        exposureAtHit=currentExposure;
        beforeHit=dragon.getHealth();healthAtHit=bot.mc.player.getHealth();
        bot.lookExact(crystal.x,crystal.y+1.6,crystal.z);LegacyFluidTask.syncAim(bot);
        bot.mc.interactionManager.attackEntity(bot.mc.player,crystal);bot.mc.player.swingHand();
        blasts++;bot.say((charging ? "Incoming dragon charge" : "Dragon healing pass")+": detonating at head distance "
                +String.format(java.util.Locale.ROOT,"%.2f",Math.sqrt(now))
                +", exposure "+exposureAtHit+".");
        stage(6,bot);return true;
    }
    private boolean validShot(LegacyBot bot,PearlTrajectory.Shot candidate,BlockPos goal) {
        for(int i=1;i<candidate.points.size();i++) {
            double[] a=candidate.points.get(i-1),b=candidate.points.get(i);
            Vec3d from=new Vec3d(a[0],a[1],a[2]),to=new Vec3d(b[0],b[1]-.015,b[2]);
            for(Object o:bot.mc.world.loadedEntities) if(o instanceof EndCrystalEntity && !((Entity)o).removed) {
                Box box=((Entity)o).getBoundingBox().expand(.65,.65,.65);
                if(box.contains(from) || box.method_585(from,to)!=null) return false;
            }
            BlockHitResult hit=bot.mc.world.rayTrace(from,to);
            if(hit!=null) return i==candidate.points.size()-1 && hit.direction==Direction.UP
                    && hit.getBlockPos().getY()==goal.getY()-1
                    && Math.abs(hit.pos.x-(goal.getX()+.5))<.6 && Math.abs(hit.pos.z-(goal.getZ()+.5))<.6;
        }
        return false;
    }
    private boolean planPearl(LegacyBot bot) {
        for(EndCrystalEntity e:crystals(bot)) {
            int cx=(int)Math.floor(e.x),y=(int)Math.floor(e.y),cz=(int)Math.floor(e.z);
            for(int[] d:new int[][]{{3,0},{-3,0},{0,3},{0,-3},{2,0},{-2,0},{0,2},{0,-2}}) {
                BlockPos p=new BlockPos(cx+d[0],y,cz+d[1]);
                if(!PathSearch.standing(bot.navigation,p.getX(),y,p.getZ())) continue;
                // Leave room for pearl spread and a normal 0.6-wide player landing.
                boolean supported=true;
                for(int[] side:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                    if(!bot.navigation.floor(p.getX()+side[0],y-1,p.getZ()+side[1])) supported=false;
                if(!supported || bot.mc.world.getBlockState(p.down()).getBlock()!=Block.get("obsidian")) continue;
                PearlTrajectory.Shot candidate=PearlTrajectory.solve(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z,p.getX()+.5,y,p.getZ()+.5);
                if(candidate==null || !validShot(bot,candidate,p)) continue;
                crystal=e;landing=p;shot=candidate;returningToGround=false;
                if(charge!=null && e.getEntityId()==charge.healingCrystal)
                    bot.say("Following the crystal the dragon is using for healing.");
                return true;
            }
        }
        return false;
    }
    private boolean planBow(LegacyBot bot) {
        if(!clearingEarlyCrystals(bot)
                || bot.count(LegacyItems.parse("bow"))==0 || bot.count(LegacyItems.parse("arrow"))==0) return false;
        PathSearch.Pos feet=bot.navigation.feet();
        if(bot.mc.world.getBlockState(new BlockPos(feet.x,feet.y-1,feet.z)).getBlock()!=Block.get("end_stone")) return false;
        Vec3d here=new Vec3d(bot.mc.player.x,bot.mc.player.y,bot.mc.player.z);
        if(lastBowOrigin==null || here.squaredDistanceTo(lastBowOrigin)>12*12) {
            rejectedArrows.clear();missedArrows.clear();lastBowOrigin=here;
        }
        for(EndCrystalEntity candidate:crystals(bot)) {
            if(rejectedArrows.contains(candidate.getUuid())) continue;
            CrystalArrowTrajectory.Shot solution=CrystalArrowTrajectory.solve(bot.mc.world,
                    bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z,candidate);
            if(solution==null) continue;
            arrowTarget=candidate;arrowShot=solution;bowDrawStarted=0;bowReleasedAt=0;
            stage(8,bot);return true;
        }
        return false;
    }
    private boolean shootEarlyCrystal(LegacyBot bot) {
        status="shoot all remaining crystals before fighting dragon";
        if(arrowTarget.removed) {
            used.add(arrowTarget.getUuid());
            bot.say("Ranged crystal cleared; "+crystals(bot).size()+" intact crystals remain.");
            arrowTarget=null;stage(0,bot);return false;
        }
        if(bowReleasedAt>0) {
            bot.key(bot.mc.options.useKey,false);
            if(bot.ticks-bowReleasedAt>Math.max(30,arrowShot.ticks+12)) {
                int misses=missedArrows.merge(arrowTarget.getUuid(),1,Integer::sum);
                if(misses>=4) rejectedArrows.add(arrowTarget.getUuid());
                bot.say("Arrow missed crystal at "+(int)arrowTarget.x+","+(int)arrowTarget.z
                        +" ("+misses+"/4); "+(misses>=4 ? "replanning another route." : "trying again."));
                arrowTarget=null;stage(0,bot);
            }
            return false;
        }
        if(bowDrawStarted==0) {
            bot.releaseMovement();bot.equip(bot.findInventory(LegacyItems.parse("bow")));
            bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
            bowDrawStarted=bot.ticks;
            bot.key(bot.mc.options.useKey,true);
            return false;
        }
        if(bot.ticks-bowDrawStarted<22) {
            bot.look(arrowTarget.x,arrowTarget.y+1,arrowTarget.z);
            bot.key(bot.mc.options.useKey,true);return false;
        }
        bot.mc.player.yaw=arrowShot.yaw;bot.mc.player.pitch=arrowShot.pitch;
        LegacyFluidTask.syncAim(bot);
        bot.key(bot.mc.options.useKey,false);
        bowReleasedAt=bot.ticks;
        bot.say("Shot early crystal at "+(int)arrowTarget.x+","+(int)arrowTarget.z
                +"; "+(crystals(bot).size()-1)+" will remain if it hits.");
        return false;
    }
    private boolean planBowVantage(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        if(bot.mc.world.getBlockState(new BlockPos(feet.x,feet.y-1,feet.z)).getBlock()!=Block.get("end_stone")) return false;
        List<EndCrystalEntity> remaining=crystals(bot);
        int[][] directions={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for(EndCrystalEntity target:remaining) for(int[] origin:new int[][]{{feet.x,feet.z},{(int)target.x,(int)target.z}})
            for(int radius:new int[]{8,16,24,32,40,48,56,64}) for(int[] direction:directions) {
                int x=origin[0]+direction[0]*radius,z=origin[1]+direction[1]*radius;
                if(!bot.mc.world.isLoaded(new BlockPos(x,feet.y,z),false)) continue;
                PathSearch.Pos site=null;
                for(int y=Math.min(90,feet.y+16);y>=Math.max(45,feet.y-16);y--)
                    if(bot.mc.world.getBlockState(new BlockPos(x,y-1,z)).getBlock()==Block.get("end_stone")
                            && PathSearch.standing(bot.navigation,x,y,z)) {site=new PathSearch.Pos(x,y,z);break;}
                if(site==null || site.equals(feet)) continue;
                Vec3d eye=new Vec3d(site.x+.5,site.y+bot.mc.player.getEyeHeight(),site.z+.5);
                // The bow arc can clear a tower even when a straight eye ray
                // hits it. For the last crystal, inspect the actual arc.
                if(remaining.size()>1 && bot.mc.world.rayTrace(eye,new Vec3d(target.x,target.y+1,target.z))!=null) continue;
                if(CrystalArrowTrajectory.solve(bot.mc.world,eye.x,eye.y,eye.z,target)==null) continue;
                List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,site,5000);
                if(route.isEmpty() || !route.get(route.size()-1).equals(site)) continue;
                bowVantage=site;
                bot.say("Moving to clear bow angle for crystal at "+(int)target.x+","+(int)target.z+".");
                return true;
            }
        return false;
    }
    private boolean defendEnderman(LegacyBot bot) {
        if(!bot.mc.player.onGround) return false;
        EndermanEntity threat=null;double nearest=Double.POSITIVE_INFINITY;
        for(Object entry:bot.mc.world.loadedEntities) if(entry instanceof EndermanEntity) {
            EndermanEntity e=(EndermanEntity)entry;
            if(!e.isAlive() || e.removed) continue;
            double distance=bot.mc.player.squaredDistanceTo(e);
            if(distance>12*12 || distance>=nearest) continue;
            if(e.getTarget()!=bot.mc.player && bot.mc.player.getAttacker()!=e && !e.isAngry()) continue;
            threat=e;nearest=distance;
        }
        if(threat==null) {defendingEnderman=null;return false;}
        if(nearest>6*6) return false;
        if(!threat.getUuid().equals(defendingEnderman)) {
            defendingEnderman=threat.getUuid();bot.say("Enderman attacking; defending with sword.");
        }
        if(child!=null) {child.stop(bot);child=null;}
        if(stage==8) {arrowTarget=null;stage(0,bot);}
        bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.useKey,false);
        int sword=bot.findInventory(LegacyItems.parse("iron_sword"));
        if(sword<0) sword=bot.findInventory(LegacyItems.parse("stone_sword"));
        if(sword<0) throw new TaskFailure("An Enderman attacked, but no sword is available.");
        bot.equip(sword);status="defend against attacking Enderman";
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        Vec3d body=new Vec3d(threat.x,threat.y+.6,threat.z);
        bot.lookExact(body.x,body.y,body.z);LegacyFluidTask.syncAim(bot);
        if(eye.squaredDistanceTo(body)<3.3*3.3 && bot.mc.world.rayTrace(eye,body)==null
                && bot.ticks-lastEndermanHitTick>=8) {
            bot.mc.interactionManager.attackEntity(bot.mc.player,threat);bot.mc.player.swingHand();lastEndermanHitTick=bot.ticks;
        } else bot.key(bot.mc.options.useKey,true);
        return true;
    }
    private boolean strikeDragon(LegacyBot bot) {
        if(stage!=7 || lastSwordHitTick==bot.ticks) return false;
        int sword=bot.findInventory(LegacyItems.parse("iron_sword"));
        if(sword<0) sword=bot.findInventory(LegacyItems.parse("stone_sword"));
        if(sword<0) throw new TaskFailure("No sword remaining to finish the dragon.");
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        EnderDragonPart chosen=null;Vec3d aim=null;double best=Double.POSITIVE_INFINITY;
        for(EnderDragonPart part:dragon.parts) {
            Box box=part.getBoundingBox();
            double x=Math.max(box.minX,Math.min(eye.x,box.maxX));
            double y=Math.max(box.minY,Math.min(eye.y,box.maxY));
            double z=Math.max(box.minZ,Math.min(eye.z,box.maxZ));
            double distance=eye.squaredDistanceTo(new Vec3d(x,y,z));
            if(distance>3.25*3.25) continue;
            double score=distance-(part==dragon.partHead ? 100 : part==dragon.partBody ? 1 : 0);
            if(score<best) {best=score;chosen=part;aim=new Vec3d(x,y,z);}
        }
        if(chosen==null) return false;
        bot.equip(sword);
        if(eye.squaredDistanceTo(aim)<.01)
            aim=new Vec3d(chosen.x,chosen.y+chosen.height*.5,chosen.z);
        bot.look(aim.x,aim.y,aim.z);LegacyFluidTask.syncAim(bot);
        bot.mc.interactionManager.attackEntity(bot.mc.player,chosen);bot.mc.player.swingHand();
        lastSwordHitTick=bot.ticks;
        if(bot.ticks%20==0) bot.say("Sword attacking dragon "+(chosen==dragon.partHead ? "head" : "body")
                +"; health "+dragon.getHealth()+".");
        return true;
    }
    private boolean returnToArena(LegacyBot bot) {
        double radius=Math.hypot(bot.mc.player.x,bot.mc.player.z);
        if(radius<=32 || !bot.mc.player.onGround) {arenaGoal=null;return false;}
        if(arenaGoal==null) {
            PathSearch.Pos feet=bot.navigation.feet();
            for(int ring:new int[]{64,48,32,24,16,8}) {
                for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                    int x=direction[0]*ring,z=direction[1]*ring;
                    if(Math.hypot(x-bot.mc.player.x,z-bot.mc.player.z)>40
                            || Math.hypot(x,z)>=radius-4) continue;
                    for(int y=Math.min(85,feet.y+12);y>=Math.max(45,feet.y-12);y--) {
                        if(bot.mc.world.getBlockState(new BlockPos(x,y-1,z)).getBlock()!=Block.get("end_stone")
                                || !PathSearch.standing(bot.navigation,x,y,z)) continue;
                        PathSearch.Pos candidate=new PathSearch.Pos(x,y,z);
                        List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,candidate,5000);
                        if(!route.isEmpty() && route.get(route.size()-1).equals(candidate)) {
                            arenaGoal=candidate;break;
                        }
                    }
                    if(arenaGoal!=null) break;
                }
                if(arenaGoal!=null) break;
            }
        }
        if(arenaGoal==null) return false;
        status="move to central bed-fighting ground";
        if(bot.navigation.move(arenaGoal,.25)) arenaGoal=null;
        return true;
    }
    /** Sprint across supported island ground, sideways to an incoming charge. */
    private boolean dodgeCharge(LegacyBot bot,boolean targeted) {
        if(stage!=0 && stage!=7 && stage!=8) return false;
        PathSearch.Pos feet=bot.navigation.feet();
        boolean island=bot.mc.world.getBlockState(new BlockPos(feet.x,feet.y-1,feet.z)).getBlock()==Block.get("end_stone");
        if(!island || !bot.mc.player.onGround) {dodgeGoal=null;return false;}
        if(dodgeGoal!=null && (bot.ticks>dodgeUntil ||
                Math.hypot(dodgeGoal.x+.5-bot.mc.player.x,dodgeGoal.z+.5-bot.mc.player.z)<.7)) dodgeGoal=null;
        if(dodgeGoal==null && targeted && charge!=null && bot.ticks-lastDodgeTick>18) {
            double vx=charge.vx,vz=charge.vz,speed=Math.hypot(vx,vz);
            double dx=bot.mc.player.x-charge.x,dz=bot.mc.player.z-charge.z;
            double toward=dx*vx+dz*vz;
            if(speed>.15 && toward>0 && dx*dx+dz*dz<38*38) {
                double closest=Math.min(16,toward/(speed*speed));
                double miss=Math.hypot(dx-vx*closest,dz-vz*closest);
                double height=Math.abs(bot.mc.player.y+1-(charge.y+charge.vy*closest));
                if(miss<9 && height<14) {
                    double sideX=-vz/speed,sideZ=vx/speed;
                    int preferred=dx*sideX+dz*sideZ>=0 ? 1 : -1;
                    for(int sign:new int[]{preferred,-preferred}) {
                        for(int length:new int[]{5,4,3}) {
                            PathSearch.Pos goal=new PathSearch.Pos((int)Math.floor(bot.mc.player.x+sideX*sign*length),
                                    feet.y,(int)Math.floor(bot.mc.player.z+sideZ*sign*length));
                            if(!PathSearch.standing(bot.navigation,goal.x,goal.y,goal.z)
                                    || bot.mc.world.getBlockState(new BlockPos(goal.x,goal.y-1,goal.z)).getBlock()!=Block.get("end_stone")
                                    || !PathSearch.walkStraight(bot.navigation,bot.mc.player.x,bot.mc.player.z,goal)) continue;
                            dodgeGoal=goal;dodgeUntil=bot.ticks+22;break;
                        }
                        if(dodgeGoal!=null) break;
                    }
                    if(dodgeGoal!=null) {
                        lastDodgeTick=bot.ticks;
                        if(child!=null) {child.stop(bot);child=null;}
                        if(stage==8) {bot.key(bot.mc.options.useKey,false);arrowTarget=null;stage(0,bot);}
                        bot.say("Dragon charge approaching; sprinting sideways to dodge.");
                    }
                }
            }
        }
        if(dodgeGoal==null) return false;
        status="dodge dragon charge on island ground";
        bot.releaseMovement();
        bot.look(dodgeGoal.x+.5,bot.mc.player.y+bot.mc.player.getEyeHeight(),dodgeGoal.z+.5);
        bot.key(bot.mc.options.sneakKey,false);
        bot.key(bot.mc.options.forwardKey,true);
        bot.sprint(bot.mc.player.getHungerManager().getFoodLevel()>6);
        return true;
    }
    private boolean planReturnPearl(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        // Work toward an intact tower rather than repeatedly choosing the
        // first compass direction, which can carry us away from every target.
        List<EndCrystalEntity> targets=crystals(bot);
        int count=Math.max(1,targets.size());
        for(int index=0;index<count;index++) {
            int tx=targets.isEmpty() ? 0 : (int)Math.floor(targets.get(index).x);
            int tz=targets.isEmpty() ? 0 : (int)Math.floor(targets.get(index).z);
            List<int[]> sites=new ArrayList<>();
            for(int radius:new int[]{8,16,24,32}) for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}})
                sites.add(new int[]{tx+d[0]*radius,tz+d[1]*radius});
            double dx=tx-bot.mc.player.x,dz=tz-bot.mc.player.z,length=Math.sqrt(dx*dx+dz*dz);
            if(length>1) for(int distance:new int[]{40,32,24,48})
                sites.add(new int[]{(int)Math.floor(bot.mc.player.x+dx/length*distance),
                        (int)Math.floor(bot.mc.player.z+dz/length*distance)});
            for(int[] site:sites) {
                int x=site[0],z=site[1];
                for(int y=Math.min(feet.y-10,85);y>=45;y--) {
                    BlockPos p=new BlockPos(x,y,z);
                    if(bot.mc.world.getBlockState(p.down()).getBlock()!=Block.get("end_stone")) continue;
                    if(!PathSearch.standing(bot.navigation,x,y,z)) break;
                    boolean broad=true;
                    for(int ox=-2;ox<=2;ox++) for(int oz=-2;oz<=2;oz++)
                        if(!bot.navigation.floor(x+ox,y-1,z+oz)) broad=false;
                    if(!broad) break;
                    for(Object entry:bot.mc.world.loadedEntities) {
                        Entity entity=(Entity)entry;
                        if(entity instanceof EndermanEntity && entity.isAlive() && entity.squaredDistanceTo(x+.5,y,z+.5)<64) broad=false;
                        if(entity instanceof EndCrystalEntity && !entity.removed && entity.squaredDistanceTo(x+.5,y,z+.5)<100) broad=false;
                    }
                    if(!broad) break;
                    PearlTrajectory.Shot candidate=PearlTrajectory.solve(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z,x+.5,y,z+.5);
                    if(candidate==null || !validShot(bot,candidate,p)) break;
                    landing=p;shot=candidate;crystal=null;returningToGround=true;
                    bot.say("No direct tower arc; pearling to island launch site "+p+" near tower "+tx+","+tz+".");
                    return true;
                }
            }
        }
        return false;
    }
    private void trackHead(LegacyBot bot) {
        EnderDragonPart head=dragon.partHead;
        bot.look(head.x,head.y+head.height*.5,head.z);
        LegacyFluidTask.syncAim(bot);
    }
    private void chooseCoveredStance(LegacyBot bot) {
        if(stanceChosen) return;
        stanceChosen=true;
        BlockPos center=new BlockPos((int)Math.floor(crystal.x),cover.getY(),(int)Math.floor(crystal.z));
        double best=Double.POSITIVE_INFINITY;
        BlockPos chosen=stance;
        for(int radial:new int[]{3,4}) for(int lateral:new int[]{-2,-1,0,1,2}) {
            BlockPos p=center.add(dx*radial-dz*lateral,0,dz*radial+dx*lateral);
            if(Math.abs(p.getX()-cover.getX())+Math.abs(p.getZ()-cover.getZ())>1) continue;
            if(!PathSearch.standing(bot.navigation,p.getX(),p.getY(),p.getZ())) continue;
            if(bot.mc.world.getBlockState(p.down()).getBlock()!=Block.get("obsidian")) continue;
            double distance=(p.getX()+.5-crystal.x)*(p.getX()+.5-crystal.x)
                    +(p.getZ()+.5-crystal.z)*(p.getZ()+.5-crystal.z);
            if(distance>13.5) continue;
            Vec3d eye=new Vec3d(p.getX()+.5,p.getY()+bot.mc.player.getEyeHeight(),p.getZ()+.5);
            if(bot.mc.world.rayTrace(eye,new Vec3d(crystal.x,crystal.y+1.6,crystal.z))!=null) continue;
            Box box=new Box(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.8,p.getZ()+.8);
            double exposure=bot.mc.world.method_3612(new Vec3d(crystal.x,crystal.y,crystal.z),box);
            double score=exposure+distance*.002;
            if(score<best) {best=score;chosen=p;}
        }
        stance=chosen;
        bot.say("Covered crystal stance "+stance+"; predicted exposure "
                +String.format(java.util.Locale.ROOT,"%.2f",best)+".");
    }
    // Walk cardinal cell centers on the supported outer ring. Generic path
    // smoothing can cut through the crystal/fire or jump onto its bedrock.
    private boolean moveOnTop(LegacyBot bot,BlockPos target) {
        bot.releaseMovement();
        if(!supported(bot)) return false;
        if(!target.equals(routeGoal)) {topRoute.clear();routeGoal=target;}
        if(topRoute.isEmpty()) {
            PathSearch.Pos start=bot.navigation.feet();
            PathSearch.Pos goal=new PathSearch.Pos(target.getX(),target.getY(),target.getZ());
            Map<PathSearch.Pos,PathSearch.Pos> parents=new HashMap<>();
            Deque<PathSearch.Pos> queue=new ArrayDeque<>();queue.add(start);parents.put(start,null);
            int cx=(int)Math.floor(crystal.x),cz=(int)Math.floor(crystal.z);
            while(!queue.isEmpty() && !parents.containsKey(goal)) {
                PathSearch.Pos p=queue.remove();
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    PathSearch.Pos n=new PathSearch.Pos(p.x+d[0],p.y,p.z+d[1]);
                    int ax=Math.abs(n.x-cx),az=Math.abs(n.z-cz);
                    if(Math.max(ax,az)>5 || ax<=1 && az<=1 || parents.containsKey(n)
                            || !PathSearch.standing(bot.navigation,n.x,n.y,n.z)) continue;
                    parents.put(n,p);queue.add(n);
                }
            }
            if(!parents.containsKey(goal)) {status="wait for supported route outside crystal fire";return false;}
            for(PathSearch.Pos p=goal;p!=null;p=parents.get(p)) topRoute.addFirst(p);
        }
        // Consume reached waypoints without inserting a stopped tick at every cell.
        PathSearch.Pos next=topRoute.peek();
        double x=next.x+.5-bot.mc.player.x,z=next.z+.5-bot.mc.player.z;
        while(x*x+z*z<.04) {
            topRoute.remove();
            if(topRoute.isEmpty()) return true;
            next=topRoute.peek();
            x=next.x+.5-bot.mc.player.x;z=next.z+.5-bot.mc.player.z;
        }
        bot.look(next.x+.5,bot.mc.player.y+bot.mc.player.getEyeHeight(),next.z+.5);
        bot.key(bot.mc.options.forwardKey,true);
        // A neighboring air cell does not make walking along a ledge unsafe.
        // Crouch only if the projected player footprint would lose support.
        double distance=Math.sqrt(x*x+z*z);
        double px=bot.mc.player.x+x/distance*.3+bot.mc.player.velocityX;
        double pz=bot.mc.player.z+z/distance*.3+bot.mc.player.velocityZ;
        int floorY=bot.navigation.feet().y-1;
        boolean edge=false;
        for(double ox:new double[]{-.3,.3}) for(double oz:new double[]{-.3,.3})
            if(!bot.navigation.floor((int)Math.floor(px+ox),floorY,(int)Math.floor(pz+oz))) edge=true;
        bot.key(bot.mc.options.sneakKey,edge);
        return false;
    }
    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension!=1) throw new TaskFailure("@dragon requires the End. Test teleport is provided only by the isolated test driver.");
        if(++ticks>24000) throw new TaskFailure("End crystal fight timed out.");
        for(Object e:bot.mc.world.loadedEntities) if(e instanceof EnderDragonEntity) {dragon=(EnderDragonEntity)e;break;}
        if(dragon==null) {if(ticks>200) throw new TaskFailure("No loaded Ender Dragon found.");return false;}
        // Multipart refreshPositionAndAngles overwrites prevX/Y/Z every tick
        // in 1.8. Track our own samples rather than reading a zero head velocity.
        EnderDragonPart trackedHead=dragon.partHead;
        if(lastHead!=null) {headVX=trackedHead.x-lastHead.x;headVY=trackedHead.y-lastHead.y;headVZ=trackedHead.z-lastHead.z;}
        lastHead=new Vec3d(trackedHead.x,trackedHead.y,trackedHead.z);
        charge=bot.mc.getServer()==null ? null : DragonChargeTracker.fresh(dragon.getEntityId());
        boolean targeted=charge!=null && bot.mc.player.getUuid().equals(charge.target);
        if(charge!=null) {headVX=charge.vx;headVY=charge.vy;headVZ=charge.vz;}
        if(targeted && stage>=3 && stage<=5) lastChargeTargetTick=bot.ticks;
        if(targeted && !wasTargeted) bot.say("Dragon selected us as its charge target; tracking its approach.");
        wasTargeted=targeted;
        if(dragon.getHealth()<=0) {bot.say("Ender Dragon defeated using "+blasts+" timed crystal detonations.");return true;}
        if(stage>=3 && stage<=5 && supported(bot) && Math.abs(bot.mc.player.y-landing.getY())>1.5) {
            if(child!=null) {child.stop(bot);child=null;}
            bot.say("Lost pillar footing; returning by pearl instead of building from below.");stage(0,bot);return false;
        }
        if(defendEnderman(bot)) return false;
        strikeDragon(bot);
        if(burstOnCharge(bot)) return false;
        if(dodgeCharge(bot,targeted)) return false;
        if(stage==8) return shootEarlyCrystal(bot);
        if(stage==4 && child!=null && targeted) {
            EnderDragonPart approaching=dragon.partHead;
            double headDx=(charge==null ? approaching.x : charge.x)-bot.mc.player.x;
            double headDz=(charge==null ? approaching.z : charge.z)-bot.mc.player.z;
            if(headDx*headDx+headDz*headDz<24*24) {
                child.stop(bot);child=null;status="pause bait construction for incoming dragon";
                moveOnTop(bot,stance);return false;
            }
        }
        if(child!=null) {
            if(child.tick(bot)) {child.stop(bot);child=null;}
            else if(stage==4) {bot.sprint(false);bot.key(bot.mc.options.sneakKey,true);}
            return false;
        }
        if(stage!=1 && stage!=5 && stage!=6 && (bot.mc.player.getHungerManager().getFoodLevel()<18 || bot.mc.player.isUsingItem())) {
            int food=bot.findInventory(LegacyItems.food());
            if(food>=0 && bot.mc.player.onGround) {
                bot.releaseMovement();bot.cancelMining();bot.equip(food);
                if(!bot.mc.player.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                bot.key(bot.mc.options.useKey,true);return false;
            }
        }
        bot.key(bot.mc.options.useKey,false);
        if(stage==0) {
            status="find bow angle for remaining crystals";
            if(!supported(bot)) return false;
            if(towerTopDrill && !landed) {
                PathSearch.Pos feet=bot.navigation.feet();
                for(EndCrystalEntity e:crystals(bot)) {
                    if(Math.abs(e.y-feet.y)>.1 || bot.mc.player.squaredDistanceTo(e)>36) continue;
                    crystal=e;landing=new BlockPos(feet.x,feet.y,feet.z);
                    landed=true;stage(3,bot);return false;
                }
                throw new TaskFailure("Tower-top drill did not begin beside an intact crystal.");
            }
            if(crystals(bot).isEmpty()) {
                if((bot.mc.player.y>75 || bot.mc.player.x*bot.mc.player.x+bot.mc.player.z*bot.mc.player.z>40*40)
                        && planReturnPearl(bot)) {stage(1,bot);return false;}
                stage(7,bot);return false;
            }
            if(clearingEarlyCrystals(bot) && bot.count(LegacyItems.parse("bow"))>0
                    && bot.count(LegacyItems.parse("arrow"))>0
                    && bot.mc.world.getBlockState(new BlockPos(bot.navigation.feet().x,
                        bot.navigation.feet().y-1,bot.navigation.feet().z)).getBlock()==Block.get("obsidian")
                    && planReturnPearl(bot)) {stage(1,bot);return false;}
            if(planBow(bot)) return false;
            if(bowVantage!=null) {
                status="walk to clear bow angle";
                if(bot.navigation.move(bowVantage,.25)) bowVantage=null;
                return false;
            }
            if(clearingEarlyCrystals(bot) && bot.ticks>=nextVantagePlan) {
                nextVantagePlan=bot.ticks+40;
                if(planBowVantage(bot)) return false;
            }
            if(bot.mc.player.getHealth()<12) {status="recover health before pearl throw";bot.releaseMovement();return false;}
            if(bot.ticks<nextPlan) return false;nextPlan=bot.ticks+40;
            if(bot.count(LegacyItems.parse("ender_pearl"))==0) throw new TaskFailure("No ender pearls remaining.");
            if(planPearl(bot)) {stage(1,bot);return false;}
            if(!clearingEarlyCrystals(bot) || crystals(bot).size()>1) {
                if(planReturnPearl(bot)) {stage(1,bot);return false;}
            }
            List<EndCrystalEntity> available=crystals(bot);
            if(available.isEmpty()) throw new TaskFailure("No intact loaded crystals remain for this strategy.");
            if(clearingEarlyCrystals(bot) && available.size()==1) {
                status="searching ground for bow arc to final crystal";
                return false;
            }
            int targetY=Math.max((int)available.get(0).y-12,bot.navigation.feet().y+8);
            if(targetY<=available.get(0).y+16 && targetY<125) {
                status="raise launch position into pearl range";
                child=new SurfaceAccessTask(targetY,true);return false;
            }
            throw new TaskFailure("No safe pearl arc to a sufficiently wide obsidian tower from this position.");
        }
        if(stage==1) {
            if(!returningToGround && !crystalAlive()) {stage(0,bot);return false;}
            if(!stableForPearl(bot)) {status="steady before pearl throw";stage(0,bot);return false;}
            if(!movingEntitiesClear(bot)) {status="wait for dragon/mobs to clear pearl flight path";bot.releaseMovement();return false;}
            int slot=bot.findInventory(LegacyItems.parse("ender_pearl"));
            bot.releaseMovement();bot.cancelMining();bot.equip(slot);
            bot.mc.player.yaw=shot.yaw;bot.mc.player.pitch=shot.pitch;LegacyFluidTask.syncAim(bot);
            landingTicks=0;
            bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
            bot.key(bot.mc.options.sneakKey,true);
            double[] predicted=shot.points.get(shot.points.size()-1);
            shots++;bot.say("Pearling to pillar top "+landing+" without hitting its crystal; yaw="+shot.yaw+", pitch="+shot.pitch
                    +", launch="+String.format(java.util.Locale.ROOT,"%.2f %.2f %.2f",bot.mc.player.x,bot.mc.player.y,bot.mc.player.z)
                    +", predicted="+String.format(java.util.Locale.ROOT,"%.2f %.2f %.2f",predicted[0],predicted[1],predicted[2])+".");stage(2,bot);return false;
        }
        if(stage==2) {
            status="verify pearl landing and stable obsidian footing";bot.releaseMovement();bot.key(bot.mc.options.sneakKey,true);
            if(!returningToGround && !crystalAlive()) {
                bot.say("Crystal disappeared during pearl flight; choosing another intact pillar.");
                used.add(crystal.getUuid());stage(0,bot);return false;
            }
            PathSearch.Pos feet=bot.navigation.feet();
            if(supported(bot) && Math.abs(bot.mc.player.y-landing.getY())<.05
                    && bot.mc.world.getBlockState(new BlockPos(feet.x,feet.y-1,feet.z)).getBlock()==Block.get(returningToGround ? "end_stone" : "obsidian")
                    && bot.mc.player.squaredDistanceToCenter(landing)<16) {
                boolean arrived=bot.navigation.move(new PathSearch.Pos(landing.getX(),landing.getY(),landing.getZ()),.12);
                bot.sprint(false);bot.key(bot.mc.options.sneakKey,true);
                if(arrived && ++landingTicks>=10) {
                    if(returningToGround) {bot.say("Stable island landing confirmed; replanning remaining towers.");returningToGround=false;stage(0,bot);}
                    else {landed=true;bot.say("Stable obsidian landing confirmed; crystal intact.");stage(3,bot);}
                }
                return false;
            }
            landingTicks=0;
            if(bot.ticks-stageSince>100 && supported(bot)) {
                bot.say("Pearl landed short on supported ground; replanning without touching the crystal.");stage(0,bot);return false;
            }
            if(bot.ticks-stageSince>160) throw new TaskFailure("Pearl did not land on the planned pillar top.");
            return false;
        }
        if(stage==3) {
            status="choose diagonal bait position before dragon charge";
            bot.releaseMovement();trackHead(bot);
            if(!crystalAlive()) {used.add(crystal.getUuid());stage(0,bot);return false;}
            EnderDragonPart head=dragon.partHead;
            double hx=(charge==null ? head.x : charge.x)-crystal.x,hz=(charge==null ? head.z : charge.z)-crystal.z;
            double distance=Math.sqrt(hx*hx+hz*hz);
            // Place the bait while the dragon is away. Waiting for it to pick
            // the player first leaves us building during the actual charge.
            if(distance<9 || !clearingEarlyCrystals(bot)
                    && (targeted && distance<32 || !healingFromCurrentCrystal() && distance<24)) return false;
            dx=Math.abs(hx)>=Math.abs(hz) ? (hx>0 ? -1 : 1) : 0;
            dz=Math.abs(hz)>Math.abs(hx) ? (hz>0 ? -1 : 1) : 0;
            BlockPos center=new BlockPos((int)Math.floor(crystal.x),landing.getY(),(int)Math.floor(crystal.z));
            int side=hx*(-dz)+hz*dx>0 ? -1 : 1;
            cover=null;stance=null;
            // A straight one-block step lets the navigator jump onto bait
            // reliably. A diagonal up-step made it circle on narrow towers.
            for(int sign:new int[]{side,-side}) for(int lateral:new int[]{1}) {
                BlockPos bait=center.add(dx*2-dz*sign,0,dz*2+dx*sign);
                BlockPos back=center.add(dx*3-dz*sign*lateral,0,dz*3+dx*sign*lateral);
                if(!PathSearch.standing(bot.navigation,bait.getX(),bait.getY(),bait.getZ())
                        || !PathSearch.standing(bot.navigation,back.getX(),back.getY(),back.getZ())) continue;
                if(bot.mc.world.getBlockState(bait.down()).getBlock()!=Block.get("obsidian")
                        || bot.mc.world.getBlockState(back.down()).getBlock()!=Block.get("obsidian")) continue;
                cover=bait;stance=back;break;
            }
            if(cover==null) {status="wait for supported diagonal bait and retreat cells";return false;}
            retreating=false;stanceChosen=false;
            stage(4,bot);return false;
        }
        if(stage==4) {
            status="place diagonal bait block and climb onto it";
            EnderDragonPart approaching=dragon.partHead;
            double headDx=(charge==null ? approaching.x : charge.x)-bot.mc.player.x;
            double headDz=(charge==null ? approaching.z : charge.z)-bot.mc.player.z;
            if(targeted && headDx*headDx+headDz*headDz<24*24) {
                status="move behind bait before incoming charge";
                moveOnTop(bot,stance);return false;
            }
            if(!bot.navigation.floor(cover.getX(),cover.getY(),cover.getZ())) {
                if(!moveOnTop(bot,stance)) return false;
                child=new LegacyPlaceTask(cover,block(bot));return false;
            }
            baitPlaced=true;
            chooseCoveredStance(bot);
            boolean perched=bot.navigation.move(new PathSearch.Pos(cover.getX(),cover.getY()+1,cover.getZ()),.15);
            if(perched) {baitPerched=true;stage(5,bot);bot.say("On diagonal bait block; tracking dragon charge.");}
            return false;
        }
        if(stage==5) {
            status=retreating ? (clearingEarlyCrystals(bot) ? "clear early crystal from covered stance" : "fall back and time crystal under dragon head")
                    : "jump on bait block and watch dragon charge";
            if(!crystalAlive()) {used.add(crystal.getUuid());stage(0,bot);return false;}
            EnderDragonPart head=dragon.partHead;
            double hx=(charge==null ? head.x : charge.x)-crystal.x;
            double hz=(charge==null ? head.z : charge.z)-crystal.z;
            double hy=(charge==null ? head.y : charge.y)-crystal.y;
            double vx=headVX,vy=headVY,vz=headVZ;
            if(!retreating) {
                bot.releaseMovement();bot.key(bot.mc.options.jumpKey,true);trackHead(bot);
                double baitX=bot.mc.player.x-(cover.getX()+.5),baitZ=bot.mc.player.z-(cover.getZ()+.5);
                if(baitX*baitX+baitZ*baitZ>1.2 || bot.mc.player.y<cover.getY()-.1) {
                    if(targeted) retreating=true;
                    else stage(4,bot);
                    return false;
                }
                double fromHeadX=head.x-bot.mc.player.x,fromHeadZ=head.z-bot.mc.player.z;
                // The server selects its target before the visible dive. Once
                // it selects us, retreat immediately; waiting for the head to
                // get close leaves too little time to step off the bait block.
                if(targeted || bot.ticks-stageSince>6 || fromHeadX*fromHeadX+fromHeadZ*fromHeadZ<42*42
                        && hx*vx+hz*vz<-.02) {
                    retreating=true;bot.say("Stepping behind bait block to covered stance "+stance+".");
                }
                return false;
            }
            if(!bot.navigation.move(new PathSearch.Pos(stance.getX(),stance.getY(),stance.getZ()),.15)) return false;
            retreated=true;
            bot.releaseMovement();trackHead(bot);
            // Breaking a healer is useful even if the dragon never selects a
            // charge. Waiting forever lets it regenerate all earlier damage.
            if(clearingEarlyCrystals(bot)
                    && bot.mc.player.squaredDistanceTo(crystal)<=16) {
                Vec3d explosion=new Vec3d(crystal.x,crystal.y,crystal.z);
                double exposure=bot.mc.world.method_3612(explosion,bot.mc.player.getBoundingBox());
                Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
                Vec3d aim=new Vec3d(crystal.x,crystal.y+1.6,crystal.z);
                if(exposure<=.35 && bot.mc.world.rayTrace(eye,aim)==null) {
                    int bow=bot.findInventory(LegacyItems.parse("bow"));
                    if(bow<0 || bot.count(LegacyItems.parse("arrow"))==0)
                        throw new TaskFailure("Bow and arrows are needed to clear the remaining tower crystal.");
                    if(towerBowReleasedAt>0 && bot.ticks-towerBowReleasedAt<20) return false;
                    if(towerBowReleasedAt>0) {
                        towerBowReleasedAt=0;towerBowStarted=0;
                        if(++towerBowAttempts>4) throw new TaskFailure("Four covered bow shots missed the tower crystal.");
                    }
                    if(towerBowStarted==0) {
                        bot.equip(bow);
                        bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                        towerBowStarted=bot.ticks;
                    }
                    if(bot.ticks-towerBowStarted<22) {
                        bot.look(aim.x,aim.y,aim.z);bot.key(bot.mc.options.useKey,true);return false;
                    }
                    exposureAtHit=exposure;beforeHit=dragon.getHealth();healthAtHit=bot.mc.player.getHealth();
                    bot.lookExact(aim.x,aim.y,aim.z);LegacyFluidTask.syncAim(bot);
                    bot.key(bot.mc.options.useKey,false);towerBowReleasedAt=bot.ticks;
                    bot.say("Shot covered tower crystal with bow; "+(crystals(bot).size()-1)
                            +" will remain if it hits.");
                    return false;
                }
            }
            if(clearingEarlyCrystals(bot)) return false;
            // Prefer the authoritative head sample, with at most a one-tick
            // prediction. The head must already be near before attacking.
            double near=hx*hx+hz*hz,predicted=(hx+vx)*(hx+vx)+(hz+vz)*(hz+vz);
            boolean overhead=near<20.25 && predicted<16 && hy>=-1.5 && hy+vy<6;
            // The charge handler above owns crystal timing after the server
            // selects us, including the short period after it clears its
            // target. This ordinary hit path is for non-charge passes.
            if(!clearingEarlyCrystals(bot) && overhead && !activeCharge(bot) && !healingFromCurrentCrystal()) {
                bot.key(bot.mc.options.sneakKey,true);
                Vec3d explosion=new Vec3d(crystal.x,crystal.y,crystal.z);
                exposureAtHit=bot.mc.world.method_3612(explosion,bot.mc.player.getBoundingBox());
                if(exposureAtHit>.35) {status="bait block does not shield explosion; holding fire";return false;}
                Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
                Vec3d aim=new Vec3d(crystal.x,crystal.y+1.6,crystal.z);
                if(bot.mc.world.rayTrace(eye,aim)!=null) return false;
                if(bot.mc.player.squaredDistanceTo(crystal)>16) return false;
                beforeHit=dragon.getHealth();healthAtHit=bot.mc.player.getHealth();
                bot.lookExact(aim.x,aim.y,aim.z);LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.attackEntity(bot.mc.player,crystal);bot.mc.player.swingHand();
                blasts++;bot.say("Timed crystal hit: head offset "+String.format(java.util.Locale.ROOT,"%.2f %.2f %.2f",hx,hy,hz)+", exposure "+exposureAtHit);
                stage(6,bot);return false;
            }
            return false;
        }
        if(stage==6) {
            status="verify crystal damage and survival";bot.releaseMovement();bot.key(bot.mc.options.sneakKey,true);
            damage=Math.max(damage,beforeHit-dragon.getHealth());
            // Wait for actual removal, not a fixed one-second pause. Keep the
            // longer measurement window only in the single-crystal drill.
            if(crystalAlive()) {
                if(bot.ticks-stageSince>=20) throw new TaskFailure("Timed crystal attack was rejected.");
                return false;
            }
            if(oneCrystal && bot.ticks-stageSince<20) return false;
            if(!oneCrystal) {
                stableAfterBlast=stableForPearl(bot) ? stableAfterBlast+1 : 0;
                if(stableAfterBlast<3) {
                    status="steady footing after crystal blast before next pearl";
                    if(bot.ticks-stageSince<100) return false;
                    bot.say("Blast knocked us off the pillar; replanning from stable ground.");
                }
            }
            bot.say("Crystal result: dragon damage "+(beforeHit-dragon.getHealth())+", dragon health "
                    +dragon.getHealth()+", player damage "+(healthAtHit-bot.mc.player.getHealth())+".");
            used.add(crystal.getUuid());
            if(oneCrystal) return true;
            crystal=null;nextPlan=0;stableAfterBlast=0;stage(0,bot);
            if(stableForPearl(bot) && bot.mc.player.getHealth()>=12
                    && bot.count(LegacyItems.parse("ender_pearl"))>0 && planPearl(bot)) {
                status="pearl to next intact crystal";stage(1,bot);
            }
            return false;
        }
        if(stage==7) {
            status="sword fight after destroying all crystals (dragon health "+dragon.getHealth()+")";
            if(bot.mc.player.x*bot.mc.player.x+bot.mc.player.z*bot.mc.player.z>40*40
                    && bot.ticks%40==0 && stableForPearl(bot) && planReturnPearl(bot)) {
                stage(1,bot);return false;
            }
            bot.releaseMovement();bot.key(bot.mc.options.sneakKey,false);
            if(returnToArena(bot)) return false;
            int sword=bot.findInventory(LegacyItems.parse("iron_sword"));
            if(sword<0) sword=bot.findInventory(LegacyItems.parse("stone_sword"));
            if(sword<0) throw new TaskFailure("No sword remaining to finish the dragon.");
            bot.equip(sword);
            EnderDragonPart head=dragon.partHead;
            bot.look(head.x,head.y+head.height*.5,head.z);
            return false;
        }
        return false;
    }
}
