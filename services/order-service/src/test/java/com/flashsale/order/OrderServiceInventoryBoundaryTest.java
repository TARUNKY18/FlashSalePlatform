package com.flashsale.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ACL boundary: OrderService source never references InventoryService packages. */
class OrderServiceInventoryBoundaryTest {

    // Built by concatenation so this file does not match itself.
    private static final String FORBIDDEN = "com.flashsale." + "inventory";

    @Test
    void orderServiceSourceHasNoInventoryReferences() throws IOException {
        List<Path> roots = List.of(Path.of("src/main/java"), Path.of("src/test/java"));
        roots.forEach(root -> assertTrue(Files.isDirectory(root), "missing source root " + root.toAbsolutePath()));

        List<Path> violations;
        try (Stream<Path> files = roots.stream().flatMap(OrderServiceInventoryBoundaryTest::walk)) {
            violations = files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> read(file).contains(FORBIDDEN))
                    .toList();
        }

        assertEquals(List.of(), violations);
    }

    private static Stream<Path> walk(Path root) {
        try {
            return Files.walk(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
