package dev.nucleusframework.graalvm.wmclass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AppNameResolverTest {
    private static final String PROP = "nucleus.app.id";
    private static final String TEST_ID = "com.example.KoverApp";
    private static String previous;

    @BeforeAll
    static void installAppId() {
        previous = System.getProperty(PROP);
        System.setProperty(PROP, TEST_ID);
    }

    @AfterAll
    static void restoreAppId() {
        if (previous == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, previous);
        }
    }

    @Test
    void resolveUsesSystemPropertyAndReplacesDots() {
        String name = AppNameResolver.resolve();
        assertFalse(name.isBlank());
        assertFalse(name.contains("."));
        assertEquals("com-example-KoverApp", name);
        assertEquals(name, AppNameResolver.resolve());
    }
}
