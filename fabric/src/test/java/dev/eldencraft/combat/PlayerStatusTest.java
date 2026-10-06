package dev.eldencraft.combat;

import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.eldencraft.link.NativeStatusSnapshot;
import dev.eldencraft.link.Proto;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PlayerStatusTest {
    private static MemorySegment packet(Arena arena) {
        var s = arena.allocate(Proto.PLAYER_STATUSES_BYTES, 8);
        s.set(JAVA_INT, 0, 2); s.set(JAVA_INT, 4, 42); s.set(JAVA_INT, 8, 99);
        s.set(JAVA_INT, 12, Proto.STATUSES_VALID); s.set(JAVA_LONG, 16, 1000);
        for (int i = 0; i < 7; i++) {
            s.set(JAVA_INT, Proto.PS_BUILDUP + i * 4L, (i + 1) * 10);
            s.set(JAVA_INT, Proto.PS_MAXIMUM + i * 4L, 200);
            s.set(JAVA_FLOAT, Proto.PS_REMAINING + i * 4L, i + 0.25f);
            s.set(JAVA_FLOAT, Proto.PS_DURATION + i * 4L, 30);
        }
        return s;
    }
    @Test void readsEveryStatusInNativeOrderAndConvertsSecondsToTicks() {
        try (var arena = Arena.ofConfined()) {
            var snapshot = NativeStatusSnapshot.read(packet(arena), 1050, 42, 99, true);
            assertNotNull(snapshot);
            assertEquals(7, NativeStatus.values().length);
            for (var status : NativeStatus.values()) {
                var entry = snapshot.get(status);
                assertEquals((status.ordinal() + 1) * 10, entry.buildup());
                assertEquals(5 + status.ordinal() * 20, entry.effectTicks());
                assertTrue(entry.active());
                assertEquals(entry.buildup() / 200f, entry.buildupFraction());
            }
        }
    }
    @Test void loadDeathEpochMapMismatchStaleFutureAndOldDllCannotApplyStatuses() {
        try (var arena = Arena.ofConfined()) {
            var s = packet(arena);
            assertNull(NativeStatusSnapshot.read(s, 1050, 42, 99, false));
            assertNull(NativeStatusSnapshot.read(s, 1050, 43, 99, true));
            assertNull(NativeStatusSnapshot.read(s, 1050, 42, 100, true));
            assertNull(NativeStatusSnapshot.read(s, 2001, 42, 99, true));
            assertNull(NativeStatusSnapshot.read(s, 999, 42, 99, true));
            s.set(JAVA_INT, 12, 0);
            assertNull(NativeStatusSnapshot.read(s, 1050, 42, 99, true));
        }
    }
    @Test void incompleteWritesAndCorruptValuesAreRejected() {
        try (var arena = Arena.ofConfined()) {
            var s = packet(arena);
            s.set(JAVA_INT, 0, 3);
            assertNull(NativeStatusSnapshot.read(s, 1050, 42, 99, true));
            s.set(JAVA_INT, 0, 4);
            s.set(JAVA_FLOAT, Proto.PS_REMAINING, Float.NaN);
            assertNull(NativeStatusSnapshot.read(s, 1050, 42, 99, true));
            s.set(JAVA_FLOAT, Proto.PS_REMAINING, 0);
            s.set(JAVA_INT, Proto.PS_BUILDUP, 201);
            assertNull(NativeStatusSnapshot.read(s, 1050, 42, 99, true));
        }
    }
    @Test void buildupIsNotAProcAndUntimedDeathblightUsesAnInfiniteDisplayEffect() {
        var buildup = new NativeStatusSnapshot.Entry(199, 200, 0, 0);
        assertFalse(buildup.active()); assertEquals(0, buildup.effectTicks());
        var death = new NativeStatusSnapshot.Entry(0, 200, -1, 0);
        assertTrue(death.active()); assertEquals(-1, death.effectTicks());
        assertEquals(1, death.timerFraction());
        assertTrue(PlayerStatusBridge.needsCorrection(100, 50)); // cure shortens the native timer
        assertFalse(PlayerStatusBridge.needsCorrection(101, 100));
        assertTrue(PlayerStatusBridge.needsCorrection(-1, 50));
        assertTrue(PlayerStatusBridge.needsCorrection(50, -1));
    }
    @Test void everyEffectHasATranslatedNameAndAn18PixelIcon() throws Exception {
        var root = Path.of("src/main/resources/assets/eldencraft");
        var lang = JsonParser.parseString(Files.readString(root.resolve("lang/en_us.json"))).getAsJsonObject();
        for (var status : NativeStatus.values()) {
            assertTrue(lang.has(status.translationKey()));
            var icon = ImageIO.read(root.resolve("textures/gui/sprites/mob_effect/" + status.id + ".png").toFile());
            assertEquals(18, icon.getWidth()); assertEquals(18, icon.getHeight());
            assertTrue(icon.getColorModel().hasAlpha());
        }
    }
    @Test void displayEffectsCannotApplyAnotherDamageTickOrAttributePenalty() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (var status : NativeStatus.values()) {
            var effect = new NativeStatusEffects.DisplayEffect(status.color);
            assertFalse(effect.isInstantaneous());
            assertFalse(effect.shouldApplyEffectTickThisTick(100, 0));
            effect.createModifiers(0, (attribute, modifier) -> fail("Unrequested status penalty: " + status));
        }
    }
}
