package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实验 5：旧连接已经关闭，之前排进 worker 的回调还会执行吗？
 *
 * <p>先占住 worker，让旧消息入队；关闭旧 Channel 并换成新 Channel，让新消息也入队，再放行。
 * 两轮仅改变是否在真正交付前核对 Channel 身份。预期：不检查时两条均交付；检查时只交付新消息。
 * 关闭 Socket 不会自动删除应用层执行器中已经排队的 Runnable。
 *
 * <p>断点：connect 方法内 worker lambda 的身份比较；查看 origin 与 current.get 的对象身份。
 * 检查只能丢弃尚未交付的旧连接回调，不能撤销已经调用外部系统的动作，也不解决同连接内旧目标消息。
 */
public final class StaleCallbackDemo {
    private StaleCallbackDemo() {}

    /** 运行不检查／检查两轮，对照交付列表中的 old-report 是否保留。 */
    public static void main(String[] args) throws Exception {
        run();
    }

    /** 两轮均使用真实的旧连接关闭和新连接建立，差异仅是交付前的身份条件。 */
    public static void run() throws Exception {
        compare(false);
        compare(true);
    }

    /** @param checkIdentity 是否在 worker 真正执行回调时再次核对当前连接 */
    private static void compare(boolean checkIdentity) throws Exception {
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try (LocalTcpLab lab = new LocalTcpLab()) {
            try {
                CountDownLatch workerEntered = new CountDownLatch(1);
                CompletableFuture<Void> blocked = new CompletableFuture<>();
                // 人为占住唯一 worker，保证接下来的旧／新回调都排在闸门后面。
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
                // 主线程更新、worker 读取；AtomicReference 提供可见性，此处没有演示 CAS。
                AtomicReference<Channel> current = new AtomicReference<>();
                // worker 写、主线程断言；列表仅用于安全采集实验结果，不是消息存储方案。
                List<String> delivered = new CopyOnWriteArrayList<>();
                CountDownLatch oldQueued = new CountDownLatch(1);
                Channel old = connect(lab, current, delivered, oldQueued, checkIdentity, "old");
                current.set(old);
                LocalTcpLab.send(old, "old-report");
                // 必须等旧回调已经入队才换连接，否则无法证明“已排队回调不会自动消失”。
                LabSupport.await(oldQueued);
                LabSupport.await(old.close());

                CountDownLatch newQueued = new CountDownLatch(1);
                Channel replacement = connect(lab, current, delivered, newQueued, checkIdentity, "new");
                // 当前身份从旧对象替换为新对象；即使设备名或地址相同，Channel 也不是同一个。
                current.set(replacement);
                LocalTcpLab.send(replacement, "new-report");
                LabSupport.await(newQueued);
                releaseWorker.countDown();
                LabSupport.await(blocked);
                // worker 队列屏障：这里只有一个执行器，排在两份报告后的空任务完成，
                // 才能说明前面的回调已返回。多线程池不能直接套用这个推断。
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

    /**
     * 给每条连接捕获固定 origin，再把消息交给 worker；queued 仅表示“已提交”，不是“已交付”。
     * expected 保存这次 connect 返回的 Channel；current 则会随着重连改变，两者用途不同。
     */
    private static Channel connect(LocalTcpLab lab, AtomicReference<Channel> current,
            List<String> delivered, CountDownLatch queued, boolean checkIdentity, String name)
            throws Exception {
        // 使用接收前已赋值的引用保存本次 Channel 身份；调用者建连后才发消息。
        AtomicReference<Channel> expected = new AtomicReference<>();
        Channel channel = lab.connect(name, text -> {
            Channel origin = expected.get();
            lab.worker().execute(() -> {
                // 检查放在执行时；若只在 I/O 入队时检查，排队期间发生替换仍会漏掉旧消息。
                if (checkIdentity && current.get() != origin) {
                    LabSupport.log(name, "旧 Channel 的未开始回调被丢弃");
                    return;
                }
                delivered.add(text);
                LabSupport.log(name, "交付 " + text);
            });
            // execute 已返回，说明回调已提交；worker 仍可能被前面的闸门挡住。
            queued.countDown();
        });
        expected.set(channel);
        return channel;
    }
}
