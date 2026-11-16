package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class StaleCallbackDemo {
    private StaleCallbackDemo() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        compare(false);
        compare(true);
    }

    private static void compare(boolean checkIdentity) throws Exception {
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (LocalTcpLab lab = new LocalTcpLab()) {
            try {
                CountDownLatch workerEntered = new CountDownLatch(1);
                CompletableFuture<Void> blocked = new CompletableFuture<>();
                lab.worker().execute(() -> {
                    workerEntered.countDown();
                    try {
                        LabSupport.gate(releaseWorker);
                        blocked.complete(null);
                    } catch (RuntimeException failure) {
                        blocked.completeExceptionally(failure);
                    }
                });
                LabSupport.await(workerEntered);
                AtomicReference<Channel> current = new AtomicReference<>();
                List<String> delivered = new CopyOnWriteArrayList<>();
                CountDownLatch oldQueued = new CountDownLatch(1);
                Channel old = connect(lab, current, delivered, oldQueued, checkIdentity, "old");
                current.set(old);
                LocalTcpLab.send(old, "old-report");
                LabSupport.await(oldQueued);
                LabSupport.await(old.close());

                CountDownLatch newQueued = new CountDownLatch(1);
                Channel replacement = connect(lab, current, delivered, newQueued, checkIdentity, "new");
                current.set(replacement);
                LocalTcpLab.send(replacement, "new-report");
                LabSupport.await(newQueued);
                releaseWorker.countDown();
                LabSupport.await(blocked);
                // worker 队列屏障，保证前面的两份报告已处理，再检查结果。
                LabSupport.await(lab.worker().submit(() -> {}));
                LabSupport.check(delivered.contains("new-report"), "新连接消息必须交付");
                LabSupport.check(delivered.contains("old-report") != checkIdentity,
                        "旧消息是否交付应取决于身份检查");
                lab.assertHealthy();
                LabSupport.log("结论", "checkIdentity=" + checkIdentity + "，交付=" + delivered);
            } finally {
                releaseWorker.countDown();
            }
        }
    }

    private static Channel connect(LocalTcpLab lab, AtomicReference<Channel> current,
            List<String> delivered, CountDownLatch queued, boolean checkIdentity, String name)
            throws Exception {
        // 使用接收前已赋值的引用保存本次 Channel 身份；调用者建连后才发消息。
        AtomicReference<Channel> expected = new AtomicReference<>();
        Channel channel = lab.connect(name, text -> {
            Channel origin = expected.get();
            lab.worker().execute(() -> {
                if (checkIdentity && current.get() != origin) {
                    LabSupport.log(name, "旧 Channel 的未开始回调被丢弃");
                    return;
                }
                delivered.add(text);
                LabSupport.log(name, "交付 " + text);
            });
            queued.countDown();
        });
        expected.set(channel);
        return channel;
    }
}
