package com.huidu.farmersdelight.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Reference sampler for the supported native patch shape; the live server executes its own modifiers. */
public final class RetroGenerationPlacements {
    private RetroGenerationPlacements() { }
    public record Position(int x, int y, int z) { }
    public record Result(List<Position> positions, boolean chanceMissed) { }

    public static long seed(RetroGenerationEngine.Terrain terrain, RetroGenerationSettings settings, RetroGenerationRule rule) {
        return terrain.worldSeed() ^ settings.seed() ^ ((long) terrain.chunkX() * 341873128712L)
                ^ ((long) terrain.chunkZ() * 132897987541L) ^ rule.id().hashCode();
    }

    public static Result sample(RetroGenerationEngine.Terrain terrain, RetroGenerationSettings settings, RetroGenerationRule rule) {
        Random random = new Random(seed(terrain, settings, rule));
        if (random.nextFloat() >= Math.min(1.0f, (float) settings.densityMultiplier() / rule.rarity())) {
            return new Result(List.of(), true);
        }
        int x = random.nextInt(16), z = random.nextInt(16);
        int y = terrain.surfaceY(x, z) + 1;
        if (y <= terrain.minHeight()) return new Result(List.of(), false);
        List<Position> positions = new ArrayList<>(rule.patch().tries());
        for (int i = 0; i < rule.patch().tries(); i++) {
            int dx = triangle(random, rule.patch().horizontalSpread());
            int dy = triangle(random, rule.patch().verticalSpread());
            int dz = triangle(random, rule.patch().horizontalSpread());
            positions.add(new Position(x + dx, y + dy, z + dz));
        }
        return new Result(List.copyOf(positions), false);
    }

    private static int triangle(Random random, int spread) {
        return random.nextInt(spread + 1) - random.nextInt(spread + 1);
    }
}
