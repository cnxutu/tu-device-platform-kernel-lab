package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.core.LabSupport;
import com.tu.deviceplatform.nettylab.core.LifecycleRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 实验 4：只发生一次 close，仍可能与另一个入口 register 交错。
 *
 * <p>依次演示：无锁登记遗漏一个资源、同一实例锁下关闭收齐资源、关闭先完成则拒绝后续登记。
 * CountDownLatch 精确安排“已检查但未登记”的暂停点，不靠随机调度碰运气。
 * 无锁分支出现 openResources==1 是预期的反例证据，不是实验失败。
 *
 * <p>断点：LifecycleRegistry.registerInside 的 checked.countDown 之后，以及 close 的同步块之前。
 * 观察 checked、allowRegistration、closed 和资源数量；不要只根据某次线程状态采样判断是否有竞争。
 * 此实验不用 TCP，专门隔离 volatile 可见性与 synchronized 互斥这两个概念。
 */
public final class LifecycleLockDemo {
    private LifecycleLockDemo() {}

    /** 运行三个交错方向；先看无锁漏洞，再对照有锁路径。 */
    public static void main(String[] args) throws Exception {
        run();
    }

    /** 前两轮安排登记先通过检查，最后一轮安排 close 先完成。 */
    public static void run() throws Exception {
        demonstrate(false);
        demonstrate(true);
        try (LifecycleRegistry closedFirst = new LifecycleRegistry(true)) {
            closedFirst.close();
            LabSupport.check(!closedFirst.register(new CountDownLatch(0), new CountDownLatch(0)),
                    "关闭先发生时，后续登记必须被拒绝");
        }
    }

    /** @param useMonitor register 与 close 是否同时使用 registry 的实例锁 */
    private static void demonstrate(boolean useMonitor) throws Exception {
        // 两条线程是为重现两个入口的竞争，不是模拟“每台设备分配两条线程”。
        ExecutorService threads = Executors.newFixedThreadPool(2);
        LifecycleRegistry registry = new LifecycleRegistry(useMonitor);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        try {
            Future<Boolean> registration = threads.submit(() -> registry.register(checked, allowRegistration));
            // 等登记线程读到 closed=false 并到达闸门，避免 close 抢先导致反例无法成立。
            LabSupport.await(checked);
            if (useMonitor) {
                CountDownLatch closeAttempted = new CountDownLatch(1);
                CompletableFuture<Void> closeFinished = new CompletableFuture<>();
                Future<?> closing = threads.submit(() -> {
                    // 这里只表示将要竞争锁，countDown 不证明下一行已经拿到锁或线程必为 BLOCKED。
                    closeAttempted.countDown();
                    registry.close();
                    closeFinished.complete(null);
                });
                LabSupport.await(closeAttempted);
                LabSupport.check(!closeFinished.isDone(), "登记持锁时，关闭不能进入临界区");
                LabSupport.log("主线程", "关闭已准备竞争锁；先允许登记线程离开临界区");
                // 必须先让持锁的登记线程继续，关闭线程才能拿锁；反过来等待会人为造成死锁。
                allowRegistration.countDown();
                LabSupport.check(LabSupport.await(registration), "登记应成功");
                LabSupport.await(closing);
                LabSupport.check(registry.openResources() == 0, "关闭必须收齐登记资源");
            } else {
                // 此时登记线程暂停在检查之后；本次 close 看不到“稍后才登记”的资源。
                registry.close();
                allowRegistration.countDown();
                LabSupport.check(LabSupport.await(registration), "已通过检查的线程仍会登记");
                // 故意要求出现遗漏，用来证明 volatile 并不能修复检查与使用之间的竞态。
                LabSupport.check(registry.openResources() == 1, "应稳定重现关闭后遗漏的资源");
                LabSupport.log("结论", "仅一次 close + volatile，仍可能漏掉随后登记的资源");
            }
        } finally {
            allowRegistration.countDown();
            threads.shutdown();
            LabSupport.check(threads.awaitTermination(LabSupport.WAIT_SECONDS + 2, TimeUnit.SECONDS),
                    "实验线程未退出");
            // 错误示例也必须清理故意留下的假资源。
            registry.close();
            LabSupport.check(registry.openResources() == 0, "实验资源未清理");
        }
    }
}
