package adris.altoclef.legacy;

/** Fuel arithmetic shared by queued furnace batches and regression checks. */
public final class SmeltFuelPlan {
    public static int required(int items,int progress,int burning,int storedTicks,int fuelTicks) {
        if(fuelTicks<=0) throw new IllegalArgumentException("Fuel must have a positive burn time");
        long missing=Math.max(0L,200L*items-Math.max(0,progress)-Math.max(0,burning)-Math.max(0,storedTicks));
        return (int)((missing+fuelTicks-1)/fuelTicks);
    }
    public static int furnaceCount(int amount) {return amount<=0 ? 0 : amount>=6 ? 2 : 1;}
    public static int parallelCoal(int amount) {
        int n=furnaceCount(amount),fuel=0;
        for(int i=0;i<n;i++) fuel+=required(amount/n+(i<amount%n ? 1 : 0),0,0,0,1600);
        return fuel;
    }
    private SmeltFuelPlan() {}
}
