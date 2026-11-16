package com.tu.deviceplatform.nettylab.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 少量公共观测与有界等待；实验本身不依赖 JUnit。 */
public final class LabSupport {
    public static final long WAIT_SECONDS = Long.getLong("lab.waitSeconds", 10L);
    public static final long DELAY_MILLIS = Long.getLong("lab.delayMillis", 3000L);
    private static final AtomicInteger STEP = new AtomicInteger();

    private LabSupport() {}

    public static synchronized void log(String device, String event) {
        System.out.printf("%03d [%s] [%s] %s%n", STEP.incrementAndGet(),
                Thread.currentThread().getName(), device, event);
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待实验事件超时");
    }

    public static <T> T await(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    public static void gate(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("实验等待被中断", failure);
        }
    }
}
