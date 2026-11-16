package com.tu.deviceplatform.nettylab.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实验用观测工具：统一输出线程名、显式核对条件，并给所有等待设置上限。
 * main 与 JUnit 共用它；check 不依赖 JVM 的 -ea 开关，失败会直接抛 AssertionError。
 * await 用于实验驱动线程；gate 只用于故意阻塞的教学路径，不是生产 I/O 的等待方式。
 */
public final class LabSupport {
    /** 一次事件等待的上限。IDEA 长断点可用 -Dlab.waitSeconds=300 放宽，非设备超时参数。 */
    public static final long WAIT_SECONDS = Long.getLong("lab.waitSeconds", 10L);
    /** 待取消的 AI／模拟重连延迟。调试可设 60000；增加它不会改变正常完成分支的 200ms 延迟。 */
    public static final long DELAY_MILLIS = Long.getLong("lab.delayMillis", 3000L);
    // 同一 JVM 内跨实验递增；步骤号表示日志输出顺序，不代表设备动作的全局顺序。
    private static final AtomicInteger STEP = new AtomicInteger();

    private LabSupport() {}

    /** synchronized 让一条日志完整输出；这里的类锁不负责保护任何实验的业务状态。 */
    public static synchronized void log(String device, String event) {
        System.out.printf("%03d [%s] [%s] %s%n", STEP.incrementAndGet(),
                Thread.currentThread().getName(), device, event);
    }

    /** main 中也生效的断言；message 应解释“哪个预期未成立”，方便从失败回到实验步骤。 */
    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** 等待其他线程 countDown 到零；超时即失败，避免错误实验永久挂住 Maven。 */
    public static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待实验事件超时");
    }

    /**
     * 等待完成并取得结果，既支持 JDK CompletableFuture，也支持实现 JDK Future 的 Netty Future。
     * get 会传播异常完成；禁止在执行该任务所依赖的同一个 I/O／worker 线程中调用。
     */
    public static <T> T await(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** 用 latch 稳定制造“回调尚未返回”的窗口；保留中断标记后把失败交回调用路径。 */
    public static void gate(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("实验等待被中断", failure);
        }
    }
}
