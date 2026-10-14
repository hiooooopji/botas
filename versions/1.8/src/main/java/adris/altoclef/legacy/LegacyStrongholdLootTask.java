package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.util.math.*;
import net.minecraft.world.gen.feature.FeatureState;

/** Survival expedition: tools, seed location, one library, then world spawn. */
final class LegacyStrongholdLootTask implements LegacyTask {
    private final String[] tools={"stone_pickaxe","stone_sword","stone_axe","stone_shovel"};
    private LegacyTask child,maintenance;
    private Future<BlockPos[]> location;
    private Future<List<int[]>> libraryScan;
    private BlockPos spawn,target;
    private List<int[]> libraries;
    private int phase,toolIndex,libraryIndex,nextScan;
    private boolean complete,suppliesReady,stoneBatchReady,templeVisited;
    private long startedNanos;
    private int paperBefore,booksBefore;
    boolean complete(){return complete;}
    BlockPos spawn(){return spawn;}
    static boolean hasTool(LegacyBot bot,String tool){
        return bot.count(LegacyItems.usableTool(tool))>0
                || tool.equals("stone_axe") && bot.count(LegacyItems.usableTool("iron_axe"))>0;
    }
    public String describe(){
        String[] phases={"craft stone tools","prepare seven food","travel to stronghold","locate libraries","loot one library","return to surface","return to spawn","complete"};
        String stage=child instanceof SurfaceAccessTask?"return to surface":phase==7 && !complete?"return to spawn":phases[Math.min(phase,7)];
        return "stronghold library expedition -> "+stage
                +(maintenance!=null?" -> "+maintenance.describe():child==null?"":" -> "+child.describe());
    }
    public void stop(LegacyBot bot){if(maintenance!=null)maintenance.stop(bot);if(child!=null)child.stop(bot);bot.releaseMovement();bot.cancelMining();bot.closeOwnedScreen();}
    public boolean canTickDuringServerLag(){return maintenance!=null ? maintenance.canTickDuringServerLag() : child!=null && child.canTickDuringServerLag();}
    public boolean tick(LegacyBot bot){
        if(bot.mc.getServer()==null || bot.mc.player.dimension!=0 || bot.mc.player.abilities.creativeMode)
            throw new TaskFailure("@stronghold loot requires a Survival single-player Overworld.");
        if(location==null){
            startedNanos=System.nanoTime();
            final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
            final BlockPos from=new BlockPos(bot.mc.player);
            location=server.method_10815((Callable<BlockPos[]>)()->new BlockPos[]{server.getWorld(0).getSpawnPos(),
                    server.getWorld(0).getChunkProvider().getNearestStructurePos(server.getWorld(0),"Stronghold",from)});
            paperBefore=bot.count(LegacyItems.parse("paper"));booksBefore=bot.count(LegacyItems.parse("book"));
        }
        if(target==null){
            if(!location.isDone())return false;
            try{BlockPos[] points=location.get();spawn=points[0];target=points[1];}
            catch(Exception e){throw new TaskFailure("Could not locate stronghold: "+e.getMessage());}
            if(target==null)throw new TaskFailure("No stronghold exists in this world.");
            bot.say("Survival library expedition: stronghold "+target+", return spawn "+spawn);
            boolean equipped=true;for(String tool:tools)if(!hasTool(bot,tool))equipped=false;
            if(Boolean.getBoolean("altoclef.legacy.resumeExistingWorld") && equipped && bot.count(LegacyItems.food())>0){
                phase=2;
                if((paperBefore>0 || booksBefore>0) && SurfaceAccessTask.terrainSurface(bot,(int)Math.floor(bot.mc.player.x),(int)Math.floor(bot.mc.player.z))>bot.mc.player.y){phase=3;suppliesReady=true;}
                bot.say("Resuming expedition with carried stone tools and food.");
            }
        }
        if(LegacyOverworldTask.defend(bot))return false;
        if((bot.mc.player.getHungerManager().getFoodLevel()<=14 && (!(child instanceof LegacyFoodTask) || bot.mc.player.getHungerManager().getFoodLevel()<=6)) || bot.mc.player.isUsingItem()){
            int food=bot.findInventory(LegacyItems.food());
            if(food>=0){
                bot.releaseMovement();bot.cancelMining();bot.equip(food);
                if(!bot.mc.player.isUsingItem())bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                bot.key(bot.mc.options.useKey,true);return false;
            }
        }
        bot.key(bot.mc.options.useKey,false);
        if(maintenance!=null){if(maintenance.tick(bot)){maintenance.stop(bot);maintenance=null;bot.navigation.reset();}return false;}
        if(phase>=3){
            for(String tool:new String[]{"stone_pickaxe","stone_axe"})if(!hasTool(bot,tool)){
                String replacement=tool.equals("stone_axe") && bot.count(LegacyItems.parse("iron_ingot"))>=3?"iron_axe":tool;
                maintenance=new AcquisitionTask(LegacyItems.usableTool(replacement),1,"get");
                bot.cancelMining();bot.releaseMovement();return false;
            }
        }
        if(child!=null){if(child.tick(bot)){child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(phase==0){
            while(toolIndex<tools.length && hasTool(bot,tools[toolIndex]))toolIndex++;
            if(toolIndex>0 && !templeVisited){
                templeVisited=true;child=LegacyTempleVillageTask.nearbyTemple();return false;
            }
            if(templeVisited && bot.count(LegacyItems.usableTool("iron_axe"))==0 && bot.count(LegacyItems.parse("iron_ingot"))>=3){
                child=new AcquisitionTask(LegacyItems.usableTool("iron_axe"),1,"get");return false;
            }
            if(toolIndex>0 && !stoneBatchReady){
                // Sword/axe/shovel, food furnace, and one replacement pick/axe:
                // gather their stone once while already at the first stone deposit.
                int needed=(bot.count(LegacyItems.usableTool("iron_axe"))>0?3:6)+(bot.count(LegacyItems.parse("furnace"))>0?0:8);
                for(int i=1;i<tools.length;i++)if(!hasTool(bot,tools[i]))needed+=i==1?2:i==2?3:1;
                stoneBatchReady=true;
                if(bot.count(LegacyItems.parse("cobblestone"))<needed){
                    AcquisitionTask stone=new AcquisitionTask(LegacyItems.parse("cobblestone"),needed,"get");
                    stone.protectedCobblestone=needed;child=stone;return false;
                }
            }
            if(toolIndex<tools.length){
                AcquisitionTask tool=new AcquisitionTask(LegacyItems.parse(tools[toolIndex]),1,"get");
                tool.protectedCobblestone=6;child=tool;return false;
            }
            phase++;return false;
        }
        if(phase==1){phase++;child=new LegacyFoodTask(7);return false;}
        if(phase==2){phase++;child=new StructureTravelTask(target,24);return false;}
        if(phase==3){
            if(!suppliesReady){
                if(bot.count(LegacyItems.parse("planks"))<16){
                    AcquisitionTask wood=new AcquisitionTask(LegacyItems.parse("planks"),16,"get");wood.protectedCobblestone=6;child=wood;return false;
                }
                suppliesReady=true;
            }
            if(libraryScan==null && bot.ticks>=nextScan){
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
                final BlockPos near=target;
                libraryScan=server.method_10815((Callable<List<int[]>>)()->{
                    List<int[]> result=new ArrayList<>();
                    FeatureState state=(FeatureState)server.getWorld(0).getOrCreateState(FeatureState.class,"Stronghold");
                    if(state==null)return result;
                    NbtCompound features=state.getFeatures();
                    for(Object key:features.getKeys()){
                        NbtCompound f=features.getCompound((String)key);
                        if(Math.abs(f.getInt("ChunkX")-(near.getX()>>4))>1 || Math.abs(f.getInt("ChunkZ")-(near.getZ()>>4))>1)continue;
                        NbtList children=f.getList("Children",10);
                        for(int i=0;i<children.size();i++){
                            NbtCompound piece=children.getCompound(i);
                            if(piece.getString("id").equals("SHLi")){int[] b=piece.getIntArray("BB");if(b.length==6)result.add(b.clone());}
                        }
                    }
                    return result;
                });
            }
            if(libraryScan==null || !libraryScan.isDone())return false;
            try{libraries=libraryScan.get();}catch(Exception e){throw new TaskFailure("Library scan failed: "+e.getMessage());}
            if(libraries.isEmpty())throw new TaskFailure("The nearest stronghold has no generated library to loot.");
            libraries.sort(Comparator.comparingDouble(b->bot.mc.player.squaredDistanceTo((b[0]+b[3])*.5,bot.mc.player.y,(b[2]+b[5])*.5)));
            int found=libraries.size();libraries=new ArrayList<>(libraries.subList(0,1));
            bot.say("Found "+found+" stronghold libraries; visiting only the nearest one, then returning to spawn.");phase++;return false;
        }
        if(phase==4){
            if(libraryIndex<libraries.size()){child=new Library(libraries.get(libraryIndex++));return false;}
            phase++;return false;
        }
        if(phase==5){phase++;child=new SurfaceAccessTask();return false;}
        if(phase==6){phase++;child=new StructureTravelTask(spawn,4);return false;}
        if(SurfaceAccessTask.underground(bot)){child=new SurfaceAccessTask();return false;}
        if(Math.hypot(bot.mc.player.x-spawn.getX(),bot.mc.player.z-spawn.getZ())>5)throw new TaskFailure("Return-to-spawn position was not reached.");
        long seconds=(System.nanoTime()-startedNanos)/1_000_000_000L;
        complete=true;bot.say("Back at spawn carrying "+bot.count(LegacyItems.parse("paper"))+" paper and "
                +bot.count(LegacyItems.parse("book"))+" books after visiting one library. Time: "+seconds/60+"m "+seconds%60+"s; 15-minute target "+(seconds<=900?"met":"missed")+".");return true;
    }
    private static final class Library implements LegacyTask {
        final int[] b;
        LegacyTask child;
        BlockPos shaft,mining,approaching,stashChest;
        int approachStarted,remaining,startingDrops,cleanupStarted,lastBookCount=-1,lastBookProgress;
        int shelvesSincePickup,lastPickupSweep,pickupSweepStarted;
        boolean collectingBatch;
        List<BlockPos> shelfCache;
        String action="";
        final List<BlockPos> chests=new ArrayList<>();
        final Set<BlockPos> rejected=new HashSet<>();
        final AcquisitionTask drops=new AcquisitionTask(LegacyItems.parse("book"),4096,"get");
        int phase,chestIndex,initialShelves,startingBooks,settled,nextTry,failures;
        Future<List<BlockPos>> paperCheck;
        boolean paperChecked;
        Library(int[] box){b=box;}
        public String describe(){return "library "+b[0]+" "+b[2]+" -> "+(child!=null?child.describe():phase<3?"enter library":phase==3?"loot paper chests":"harvest bookshelves ("+remaining+" left) "+action);}
        public void stop(LegacyBot bot){if(child!=null)child.stop(bot);drops.stop(bot);bot.releaseMovement();bot.cancelMining();}
        public boolean tick(LegacyBot bot){
            if(child!=null){if(child.tick(bot)){child.stop(bot);child=null;lastBookProgress=bot.ticks;bot.navigation.reset();}return false;}
            if(phase==0){
                BlockPos feet=new BlockPos(bot.mc.player);
                if(feet.getX()>=b[0] && feet.getX()<=b[3] && feet.getZ()>=b[2] && feet.getZ()<=b[5]
                        && feet.getY()>=b[1] && feet.getY()<b[4]){
                    startingBooks=bot.count(LegacyItems.parse("book"));initialShelves=blocks(bot,"bookshelf").size();
                    for(net.minecraft.entity.ItemEntity item:bookDrops(bot))startingDrops+=item.getItemStack().count;
                    chests.addAll(blocks(bot,"chest"));phase=3;
                    bot.say("Continuing inside library: "+initialShelves+" shelves remaining.");
                }else{phase++;if(SurfaceAccessTask.underground(bot)){child=new SurfaceAccessTask();return false;}}
            }
            if(phase==1){phase++;child=new StructureTravelTask(new BlockPos((b[0]+b[3])/2,80,(b[2]+b[5])/2),4);return false;}
            if(phase==2){
                if(shaft==null){
                    double best=Double.MAX_VALUE;
                    // Prefer a bookshelf column: shelves/floors break long indoor falls into short drops.
                    for(int x=b[0]+1;x<b[3];x++)for(int z=b[2]+1;z<b[5];z++){
                        int top=SurfaceAccessTask.terrainSurface(bot,x,z),low=b[1]+1;
                        if(top<low || !PathSearch.standing(bot.navigation,x,top,z))continue;
                        boolean safe=true;int air=0;
                        for(int y=top-1;y>=low-1;y--){
                            BlockPos p=new BlockPos(x,y,z);Block block=bot.mc.world.getBlockState(p).getBlock();
                            if(!bot.mc.world.isLoaded(p,false) || block.getMaterial().isFluid() || block==Block.get("bedrock") || bot.mc.world.getBlockEntity(p)!=null){safe=false;break;}
                            if(block.isFullCube())air=0;else if(++air>2){safe=false;break;}
                            for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST})
                                if(bot.mc.world.getBlockState(p.offset(d)).getBlock().getMaterial().isFluid())safe=false;
                        }
                        if(!safe || !bot.navigation.floor(x,low-1,z))continue;
                        double d=bot.mc.player.squaredDistanceTo(x+.5,bot.mc.player.y,z+.5);
                        if(d<best){best=d;shaft=new BlockPos(x,low,z);}
                    }
                    if(shaft==null)throw new TaskFailure("No safe short-drop shaft into this library.");
                    startingBooks=bot.count(LegacyItems.parse("book"));
                    for(BlockPos p:blocks(bot,"bookshelf"))initialShelves++;
                    chests.addAll(blocks(bot,"chest"));
                    chests.sort(Comparator.comparingInt(BlockPos::getY));
                    bot.say("Library has "+initialShelves+" bookshelves and "+chests.size()+" chests; access shaft "+shaft);
                }
                int top=SurfaceAccessTask.terrainSurface(bot,shaft.getX(),shaft.getZ());
                if(Math.hypot(bot.mc.player.x-shaft.getX()-.5,bot.mc.player.z-shaft.getZ()-.5)>.18){
                    bot.navigation.move(new PathSearch.Pos(shaft.getX(),top,shaft.getZ()),.12);return false;
                }
                phase++;child=new Shaft(shaft);return false;
            }
            if(phase==3){
                if(!paperChecked){
                    if(paperCheck==null){
                        final List<BlockPos> possible=new ArrayList<>(chests);
                        stashChest=possible.stream().min(Comparator.comparingInt(BlockPos::getY)).orElse(null);
                        final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
                        paperCheck=server.method_10815((Callable<List<BlockPos>>)()->{
                            List<BlockPos> result=new ArrayList<>();
                            for(BlockPos p:possible){
                                Object entity=server.getWorld(0).getBlockEntity(p);
                                if(!(entity instanceof net.minecraft.block.entity.ChestBlockEntity))continue;
                                net.minecraft.block.entity.ChestBlockEntity chest=(net.minecraft.block.entity.ChestBlockEntity)entity;
                                for(int slot=0;slot<chest.getInvSize();slot++)if(LegacyItems.parse("paper").matches(chest.getInvStack(slot))){result.add(p);break;}
                            }
                            return result;
                        });
                    }
                    if(!paperCheck.isDone()){bot.releaseMovement();return false;}
                    try{chests.clear();chests.addAll(paperCheck.get());}catch(Exception e){throw new TaskFailure("Could not inspect library chests: "+e.getMessage());}
                    paperChecked=true;
                }
                if(chestIndex<chests.size()){
                    BlockPos chest=chests.get(chestIndex);
                    if(chest.getY()>bot.mc.player.y+3){child=new SurfaceAccessTask(chest.getY(),true);return false;}
                    chestIndex++;child=new InventoryTasks.Withdraw(LegacyItems.parse("paper"),4096,chest);return false;
                }
                // Temple loot and cane take extra slots. Store incidental mining
                // debris in the library chest so all of this library's books fit.
                int free=0,bookRoom=0;
                for(ItemStack stack:bot.mc.player.inventory.main){
                    if(stack==null)free++;
                    else if(LegacyItems.parse("book").matches(stack))bookRoom+=stack.getMaxCount()-stack.count;
                }
                int booksToCollect=blocks(bot,"bookshelf").size()*3;
                for(net.minecraft.entity.ItemEntity item:bookDrops(bot))booksToCollect+=item.getItemStack().count;
                int requiredSlots=Math.max(0,(booksToCollect-bookRoom+63)/64)+2;
                if(stashChest!=null && free<requiredSlots){
                    for(String item:new String[]{"wool","sapling","stone","wooden_pickaxe"}){
                        LegacyItems.Target debris=LegacyItems.parse(item);int count=bot.count(debris);
                        if(count==0)continue;
                        InventoryTasks.Deposit deposit=new InventoryTasks.Deposit(debris,count);
                        deposit.open=new WorkstationTasks.Open("chest",net.minecraft.screen.ChestScreenHandler.class,null);
                        deposit.open.pos=stashChest;deposit.open.fixedPosition=true;child=deposit;return false;
                    }
                }
                phase++;return false;
            }
            if(mining!=null){
                if(bot.mc.world.getBlockState(mining).getBlock()==Block.get("bookshelf")){action="mine "+mining;bot.mine(mining);return false;}
                mining=null;bot.cancelMining();settled=0;
                shelvesSincePickup++;
                approaching=null;rejected.clear();bot.navigation.reset();
            }
            if(shelfCache==null)shelfCache=blocks(bot,"bookshelf");
            shelfCache.removeIf(p->bot.mc.world.getBlockState(p).getBlock()!=Block.get("bookshelf"));
            List<BlockPos> shelves=shelfCache;
            remaining=shelves.size();
            shelves.sort(Comparator.comparingDouble(p->bot.mc.player.squaredDistanceTo(p.getX()+.5,p.getY()+.5,p.getZ()+.5)));
            BlockPos reachable=null;
            for(BlockPos p:shelves)if(!p.equals(new BlockPos(bot.mc.player).down()) && bot.hit(p)!=null){reachable=p;break;}
            int carriedBooks=bot.count(LegacyItems.parse("book"));
            if(carriedBooks!=lastBookCount){lastBookCount=carriedBooks;lastBookProgress=bot.ticks;}
            boolean needsRecovery=remaining==0 && bot.ticks-lastBookProgress>160;
            // Mine a local group before sweeping its drops. Do not leave this
            // floor for a fallen book while reachable shelves are still here.
            boolean sweep=collectingBatch || shelvesSincePickup>=8 || reachable==null || bot.ticks-lastPickupSweep>=400;
            if(sweep && !needsRecovery){
                if(!collectingBatch){collectingBatch=true;pickupSweepStarted=bot.ticks;}
                if(bot.ticks-pickupSweepStarted<160 && drops.pickup(bot,item->remaining==0 || Math.abs(item.y-bot.mc.player.y)<=2.05)){
                    action="collect batch of book drops";approaching=null;settled=0;return false;
                }
                collectingBatch=false;shelvesSincePickup=0;lastPickupSweep=bot.ticks;
            }
            if(shelves.isEmpty()){
                bot.releaseMovement();
                if(cleanupStarted==0)cleanupStarted=bot.ticks;
                List<net.minecraft.entity.ItemEntity> pending=bookDrops(bot);
                if(!pending.isEmpty()){
                    action="recover "+pending.stream().mapToInt(e->e.getItemStack().count).sum()+" remaining book drops";
                    if(bot.ticks-cleanupStarted>2400)throw new TaskFailure("Could not recover remaining library book drops.");
                    // Give pickup rejection cooldowns time to expire before changing levels.
                    if(!needsRecovery)return false;
                    pending.sort(Comparator.comparingDouble(e->bot.mc.player.squaredDistanceTo(e)));
                    for(net.minecraft.entity.ItemEntity item:pending){
                        BlockPos support=new BlockPos(item.x,item.y-.01,item.z);
                        Block block=bot.mc.world.getBlockState(support).getBlock();
                        // Books can land on the upper chest's non-full-height lid. There
                        // is no integer standing cell on that lid for the walking planner.
                        if(item.y>bot.mc.player.y+2 && (block==Block.get("chest") || block==Block.get("planks"))
                                && bot.hit(support)!=null && !support.equals(new BlockPos(bot.mc.player).down())){
                            action="release books from ledge "+support;bot.mine(support);return false;
                        }
                    }
                    boolean lower=pending.stream().anyMatch(e->e.y<bot.mc.player.y-2);
                    if(lower){
                        BlockPos feet=new BlockPos(bot.mc.player);
                        int gap=0;while(gap<4 && !bot.navigation.floor(feet.getX(),feet.getY()-2-gap,feet.getZ()))gap++;
                        if(gap<3 && safeShaft(bot,feet.getX(),feet.getY(),feet.getZ())){child=new Shaft(new BlockPos(feet.getX(),b[1]+1,feet.getZ()));lastBookProgress=bot.ticks;settled=0;return false;}
                        List<BlockPos> below=new ArrayList<>();for(net.minecraft.entity.ItemEntity item:pending)below.add(new BlockPos(item));
                        LegacyTask down=lowerFloor(bot,below);if(down!=null){child=down;lastBookProgress=bot.ticks;settled=0;return false;}
                    }
                    lastBookProgress=bot.ticks; // Resume ordinary pickup retries after a recovery attempt.
                    return false;
                }
                if(++settled<40)return false;
                int gained=bot.count(LegacyItems.parse("book"))-startingBooks;
                if(gained<initialShelves*3+startingDrops)throw new TaskFailure("Bookshelves removed, but collected only "+gained+" of "+(initialShelves*3+startingDrops)+" books; refusing to leave missing drops.");
                return true;
            }
            if(!hasTool(bot,"stone_axe")){child=new AcquisitionTask(LegacyItems.usableTool("stone_axe"),1,"get");return false;}
            if(reachable!=null){mining=reachable;action="mine shelf batch "+mining;bot.mine(mining);return false;}
            // Keep one destination while its route is being searched/followed. Re-sorting
            // every tick used to restart approach planning as the player moved a few cm.
            if(approaching!=null && (bot.ticks-approachStarted>160 || !shelves.contains(approaching))){
                rejected.add(approaching);approaching=null;bot.navigation.reset();
            }
            BlockPos next=approaching;
            if(next==null)for(BlockPos p:shelves)if(!rejected.contains(p)){
                // Finish the upper floor before considering shelves hidden below it.
                if(p.getY()<bot.mc.player.y-1 && shelves.stream().anyMatch(q->!rejected.contains(q) && q.getY()>=bot.mc.player.y-1))continue;
                next=p;approaching=p;approachStarted=bot.ticks;break;
            }
            if(next==null){
                rejected.clear();
                LegacyTask down=lowerFloor(bot,shelves);
                if(down!=null){child=down;return false;}
                if(++failures>5)throw new TaskFailure("Could not reach remaining library shelves.");
                int high=shelves.stream().mapToInt(BlockPos::getY).max().orElse(b[1]);
                if(high>bot.mc.player.y+3){child=new SurfaceAccessTask(high-2,true);return false;}
                throw new TaskFailure("Remaining shelves are inaccessible from the current library floor.");
            }
            if(next.equals(new BlockPos(bot.mc.player).down())){
                child=new Shaft(new BlockPos(next.getX(),b[1]+1,next.getZ()));return false;
            }
            action="approach "+next;
            try{bot.navigation.approach(next);}catch(TaskFailure failure){rejected.add(next);approaching=null;bot.navigation.reset();}
            return false;
        }
        List<net.minecraft.entity.ItemEntity> bookDrops(LegacyBot bot){
            List<net.minecraft.entity.ItemEntity> result=new ArrayList<>();
            for(Object entry:bot.mc.world.loadedEntities)if(entry instanceof net.minecraft.entity.ItemEntity){
                net.minecraft.entity.ItemEntity item=(net.minecraft.entity.ItemEntity)entry;
                if(!item.removed && LegacyItems.parse("book").matches(item.getItemStack())
                        && item.x>=b[0]-1 && item.x<=b[3]+2 && item.z>=b[2]-1 && item.z<=b[5]+2
                        && item.y>=b[1] && item.y<=b[4]+2)result.add(item);
            }
            return result;
        }
        LegacyTask lowerFloor(LegacyBot bot,List<BlockPos> shelves){
            PathSearch.Pos from=bot.navigation.feet();
            if(shelves.stream().noneMatch(p->p.getY()<from.y-2))return null;
            List<PathSearch.Pos> columns=new ArrayList<>();
            for(int x=b[0]+1;x<b[3];x++)for(int z=b[2]+1;z<b[5];z++)for(int y=from.y+1;y>=from.y-2;y--)
                if(PathSearch.standing(bot.navigation,x,y,z) && safeShaft(bot,x,y,z))columns.add(new PathSearch.Pos(x,y,z));
            columns.sort(Comparator.comparingDouble(from::distance));
            for(PathSearch.Pos stand:columns){
                    List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,stand,3000);
                    if(!stand.equals(from) && (route.isEmpty() || !route.get(route.size()-1).equals(stand)))continue;
                    return new LegacyTask(){
                        LegacyTask dig;
                        public String describe(){return "descend to lower library floor"+(dig==null?"":" -> "+dig.describe());}
                        public void stop(LegacyBot bot){if(dig!=null)dig.stop(bot);bot.releaseMovement();bot.navigation.reset();}
                        public boolean tick(LegacyBot bot){
                            if(dig!=null)return dig.tick(bot);
                            if(bot.navigation.move(stand,.12)){bot.navigation.reset();dig=new Shaft(new BlockPos(stand.x,b[1]+1,stand.z));}
                            return false;
                        }
                    };
            }
            return null;
        }
        boolean safeShaft(LegacyBot bot,int x,int top,int z){
            int air=0;
            for(int y=top-1;y>=b[1];y--){
                BlockPos p=new BlockPos(x,y,z);Block block=bot.mc.world.getBlockState(p).getBlock();
                if(block.getMaterial().isFluid() || block==Block.get("bedrock") || bot.mc.world.getBlockEntity(p)!=null)return false;
                if(block.isFullCube())air=0;else if(++air>2)return false;
                for(Direction d:Direction.values())if(bot.mc.world.getBlockState(p.offset(d)).getBlock().getMaterial().isFluid())return false;
            }
            return bot.navigation.floor(x,b[1],z);
        }
        List<BlockPos> blocks(LegacyBot bot,String name){
            List<BlockPos> result=new ArrayList<>();Block wanted=Block.get(name);
            for(int x=b[0];x<=b[3];x++)for(int z=b[2];z<=b[5];z++)for(int y=b[1];y<=b[4];y++){
                BlockPos p=new BlockPos(x,y,z);
                if(!bot.mc.world.isLoaded(p,false))throw new TaskFailure("Library chunks are not fully loaded.");
                if(bot.mc.world.getBlockState(p).getBlock()==wanted)result.add(p);
            }
            return result;
        }
    }
    private static final class Shaft implements LegacyTask {
        final BlockPos goal;int ticks;
        Shaft(BlockPos goal){this.goal=goal;}
        public String describe(){return "mine down to library at "+goal;}
        public void stop(LegacyBot bot){bot.releaseMovement();bot.cancelMining();}
        public boolean tick(LegacyBot bot){
            if(++ticks>12000)throw new TaskFailure("Library shaft timed out.");
            if(!bot.mc.player.onGround){bot.releaseMovement();return false;}
            double dx=goal.getX()+.5-bot.mc.player.x,dz=goal.getZ()+.5-bot.mc.player.z;
            if(dx*dx+dz*dz>.0225){bot.releaseMovement();bot.look(goal.getX()+.5,bot.mc.player.y+1.62,goal.getZ()+.5);bot.key(bot.mc.options.forwardKey,true);bot.key(bot.mc.options.sneakKey,true);return false;}
            if(bot.mc.player.y<=goal.getY()+.05)return true;
            BlockPos feet=new BlockPos(bot.mc.player);
            // Tall grass can intercept every ray aimed at the floor underneath it.
            for(BlockPos p:new BlockPos[]{feet.up(),feet})if(!bot.mc.world.isAir(p)
                    && !bot.mc.world.getBlockState(p).getBlock().getMaterial().isFluid()){
                bot.mine(p);return false;
            }
            BlockPos floor=feet.down();
            int drop=0;while(drop<4 && !bot.navigation.floor(floor.getX(),floor.getY()-1-drop,floor.getZ()))drop++;
            if(drop>=3)throw new TaskFailure("Library shaft opened onto an unsafe fall.");
            for(int y=floor.getY();y>=floor.getY()-drop-1;y--)for(Direction d:Direction.values())
                if(bot.mc.world.getBlockState(new BlockPos(floor.getX(),y,floor.getZ()).offset(d)).getBlock().getMaterial().isFluid())throw new TaskFailure("Fluid exposed beside the library shaft.");
            bot.mine(floor);return false;
        }
    }
}
