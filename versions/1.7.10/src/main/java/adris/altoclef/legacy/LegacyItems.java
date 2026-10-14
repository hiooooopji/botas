package adris.altoclef.legacy;

import adris.altoclef.legacy.mixin.ShapedRecipeAccessor;
import adris.altoclef.legacy.mixin.ShapelessRecipeAccessor;
import net.minecraft.item.*;
import net.minecraft.recipe.*;
import java.util.*;

final class LegacyItems {
    static final class Target {
        final Item item;
        final int data; // -1 accepts any metadata, never any item.
        final boolean woodFamily;
        boolean foodFamily;
        boolean leafFamily;
        boolean usableTool;
        int meatFamily; // 1 raw, 2 cooked; internal aggregate targets only.
        Target(Item item, int data) { this(item, data, false); }
        Target(Item item, int data, boolean woodFamily) { this.item = item; this.data = data; this.woodFamily = woodFamily; }
        boolean matches(ItemStack s) {
            if(usableTool && !usableTool(s)) return false;
            if(leafFamily) return s!=null && s.count>0 && (id(s.getItem()).equals("leaves") || id(s.getItem()).equals("leaves2"));
            if(foodFamily) return readyFood(s);
            if(meatFamily>0) return s!=null && s.count>0 && Arrays.asList(meatFamily==1 ? new String[]{"chicken","beef","porkchop"} : new String[]{"cooked_chicken","cooked_beef","cooked_porkchop"}).contains(id(s.getItem()));
            return s != null && s.count > 0 && (s.getItem() == item || woodFamily && id(s.getItem()).equals("log2"))
                    && (data < 0 || !s.hasSubTypes() || s.getData() == data);
        }
        ItemStack stack() { return new ItemStack(item, 1, Math.max(0, data)); }
        String key() { return leafFamily ? "any_leaves" : woodFamily ? "any_logs" : id(item) + ":" + data; }
        @Override public String toString() { return foodFamily ? "food" : meatFamily>0 ? (meatFamily==1 ? "raw meat (chicken, cow or pig)" : "cooked meat") : woodFamily ? "wood logs (any species)" : id(item) + (data > 0 ? ":" + data : ""); }
    }

    static final class Recipe {
        final Target output;
        final int count, width, height;
        final Target[] ingredients;
        Recipe(ItemStack result, int width, int height, List<ItemStack> ingredients) {
            output = of(result); count = result.count;
            this.width = width; this.height = height;
            this.ingredients = new Target[ingredients.size()];
            for (int i = 0; i < ingredients.size(); i++) {
                ItemStack s = ingredients.get(i);
                this.ingredients[i] = s == null ? null : of(s);
            }
        }
        boolean needsTable() { return width > 2 || height > 2; }
    }

    static String id(Item item) { return String.valueOf(Item.REGISTRY.getId(item)).replace("minecraft:", ""); }
    static boolean usableTool(ItemStack stack) {
        return stack!=null && stack.count>0 && (!stack.isDamageable() || stack.getDamage()<stack.getMaxDamage()-1);
    }
    static Target usableTool(String id) {Target target=parse(id);target.usableTool=true;return target;}
    static Target of(ItemStack stack) { return new Target(stack.getItem(), stack.getData() == 32767 ? -1 : stack.getData()); }
    static Target meat(boolean cooked) {Target t=parse(cooked ? "cooked_beef" : "beef");t.meatFamily=cooked ? 2 : 1;return t;}
    static boolean readyFood(ItemStack s) {
        if(s==null || s.count<=0) return false;
        return Arrays.asList("carrot","bread","apple","golden_apple","baked_potato","melon","cookie",
                "mushroom_stew","pumpkin_pie","cooked_beef","cooked_porkchop",
                "cooked_chicken","cooked_fish","rotten_flesh").contains(id(s.getItem()));
    }
    static Target food() {Target t=parse("bread");t.foodFamily=true;return t;}
    static Target anyLogs() { return new Target(parse("log").item, -1, true); }
    static Target anyLeaves() {Target t=parse("leaves");t.leafFamily=true;return t;}

    static Target parse(String name) {
        name = name.toLowerCase(Locale.ROOT);
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
        for (int i = 0; i < woods.length; i++) {
            if (name.equals(woods[i] + "_log")) return parse((i < 4 ? "log:" + i : "log2:" + (i - 4)));
            if (name.equals(woods[i] + "_planks")) return parse("planks:" + i);
        }
        if (name.equals("charcoal")) name = "coal:1";
        if (name.equals("lapis_lazuli")) name = "dye:4";
        if (name.equals("wood")) name = "log";
        String[] parts = name.split(":", -1);
        int data = -1;
        if (parts.length > 1 && parts[parts.length - 1].matches("[0-9]+")) {
            try { data = Integer.parseInt(parts[parts.length - 1]); }
            catch (NumberFormatException e) { throw new TaskFailure("Invalid item metadata."); }
            if (data > 32767) throw new TaskFailure("Item metadata must be 0..32767.");
            name = name.substring(0, name.lastIndexOf(':'));
        }
        if (!name.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) throw new TaskFailure("Invalid item name.");
        Object item = Item.REGISTRY.get(name.contains(":") ? name : "minecraft:" + name);
        if (!(item instanceof Item)) throw new TaskFailure("Unknown Minecraft 1.7.10 item: " + name);
        return new Target((Item) item, data);
    }

    static List<Recipe> recipes(Target output) {
        List<Recipe> result = new ArrayList<>();
        for (Object entry : RecipeDispatcher.getInstance().getAllRecipes()) {
            RecipeType recipe = (RecipeType) entry;
            if (!output.matches(recipe.getOutput())) continue;
            if (recipe instanceof ShapedRecipeType) {
                ShapedRecipeAccessor r = (ShapedRecipeAccessor) recipe;
                result.add(new Recipe(recipe.getOutput(), r.altoclef$width(), r.altoclef$height(),
                        Arrays.asList(r.altoclef$ingredients())));
            } else if (recipe instanceof ShapelessRecipeType) {
                List<ItemStack> inputs = new ArrayList<>();
                for (Object stack : ((ShapelessRecipeAccessor) recipe).altoclef$ingredients()) inputs.add((ItemStack) stack);
                int width = inputs.size() <= 4 ? Math.min(2, inputs.size()) : 3;
                result.add(new Recipe(recipe.getOutput(), width, (inputs.size() + width - 1) / width, inputs));
            }
        }
        return result;
    }

    static Target smeltingInput(Target output) {
        for (Object entry : SmeltingRecipeRegistry.getInstance().getRecipeMap().entrySet()) {
            Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entry;
            if (output.matches((ItemStack) pair.getValue())) return of((ItemStack) pair.getKey());
        }
        return null;
    }

    private LegacyItems() {}
}
