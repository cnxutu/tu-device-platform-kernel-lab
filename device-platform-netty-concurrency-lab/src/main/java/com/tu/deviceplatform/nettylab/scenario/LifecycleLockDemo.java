package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.core.LabSupport;
import com.tu.deviceplatform.nettylab.core.LifecycleRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class LifecycleLockDemo {
    private LifecycleLockDemo() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        demonstrate(false);
        demonstrate(true);
        try (LifecycleRegistry closedFirst = new LifecycleRegistry(true)) {
            closedFirst.close();
            LabSupport.check(!closedFirst.register(new CountDownLatch(0), new CountDownLatch(0)),
                    "关闭先发生时，后续登记必须被拒绝");
        }
    }

    private static void demonstrate(boolean useMonitor) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        LifecycleRegistry registry = new LifecycleRegistry(useMonitor);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        try {
            Future<Boolean> registration = threads.submit(() -> registry.register(checked, allowRegistration));
            LabSupport.await(checked);
            if (useMonitor) {
                CountDownLatch closeAttempted = new CountDownLatch(1);
                CompletableFuture<Void> closeFinished = new CompletableFuture<>();
                Future<?> closing = threads.submit(() -> {
                    closeAttempted.countDown();
                    registry.close();
                    closeFinished.complete(null);
                });
                LabSupport.await(closeAttempted);
                LabSupport.check(!closeFinished.isDone(), "登记持锁时，关闭不能进入临界区");
                LabSupport.log("主线程", "关闭已准备竞争锁；先允许登记线程离开临界区");
                allowRegistration.countDown();
                LabSupport.check(LabSupport.await(registration), "登记应成功");
                LabSupport.await(closing);
                LabSupport.check(registry.openResources() == 0, "关闭必须收齐登记资源");
            } else {
                registry.close();
                allowRegistration.countDown();
                LabSupport.check(LabSupport.await(registration), "已通过检查的线程仍会登记");
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
