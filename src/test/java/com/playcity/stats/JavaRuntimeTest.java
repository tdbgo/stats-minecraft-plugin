package com.playcity.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaRuntimeTest {
    @Test
    void testsRunOnJava25() {
        assertEquals(25, Runtime.version().feature());
    }
}
