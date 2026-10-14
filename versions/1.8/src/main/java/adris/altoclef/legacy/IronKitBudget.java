package adris.altoclef.legacy;

/** Existing kit items replace their own recipes, never arbitrary iron requirements. */
public final class IronKitBudget {
    public static int usable(int ingots,int picks,int buckets,int igniters) {
        return Math.max(0,ingots)+3*Math.min(1,Math.max(0,picks))
                +3*Math.min(2,Math.max(0,buckets))+Math.min(1,Math.max(0,igniters));
    }
    private IronKitBudget() {}
}
