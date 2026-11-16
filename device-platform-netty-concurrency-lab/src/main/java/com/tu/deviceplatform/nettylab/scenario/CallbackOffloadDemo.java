package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 实验 2：把慢回调从 I/O 卸载到 worker，究竟解决了什么问题？
 *
 * <p>两轮使用相同的受控慢回调：false 在接收 I/O 直接执行，true 显式 execute 到 worker。
 * 用真实 TCP PING 回显观察 I/O 是否可前进，用 otherCallback 观察同一个 worker 是否可前进。
 * 预期：直接执行时 PING 被阻塞；卸载后 PING 可往返，但后续 worker 回调仍须等待慢任务。
 *
 * <p>断点：gate(release)，Suspend 设为 Thread。gate 是故意制造阻塞的教学工具，
 * 不是生产回调中应使用的操作；150ms 仅为受控观察窗口，不是设备性能或超时指标。
 */
public final class CallbackOffloadDemo {
    private CallbackOffloadDemo() {}

    /** 直接运行先看 false，再看 true；比较日志中的 adapter-io 与 adapter-worker。 */
    public static void main(String[] args) throws Exception {
        run();
    }

    /** 顺序运行两轮；每轮独立创建 TCP 与执行器，避免上一轮状态影响对照。 */
    public static void run() throws Exception {
        compare(false);
        compare(true);
    }

    /** @param offload 是否把慢任务提交给 worker，PING 接收始终留在 I/O */
    private static void compare(boolean offload) throws Exception {
        // entered 保证慢任务已经占住线程；release 决定何时放行，比猜测 sleep 时长可靠。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> callbackDone = new CompletableFuture<>();
        CompletableFuture<Void> heartbeat = new CompletableFuture<>();
        try (LocalTcpLab lab = new LocalTcpLab()) {
            try {
                Channel channel = lab.connect("slow-device", text -> {
                    if ("PING".equals(text)) {
                        // 心跳完成的证据是设备回显已回来，不是仅将 PING 放进发送队列。
                        heartbeat.complete(null);
                        return;
                    }
                    Runnable slow = () -> {
                        LabSupport.log("慢回调", "进入，offload=" + offload);
                        entered.countDown();
                        try {
                            LabSupport.gate(release);
                            callbackDone.complete(null);
                        } catch (RuntimeException failure) {
                            callbackDone.completeExceptionally(failure);
                        }
                    };
                    if (offload) {
                        // execute 只提交，不等待。真正执行 slow 的线程在 worker 中。
                        lab.worker().execute(slow);
                    } else {
                        // run 是普通方法调用，继续使用当前 I/O 线程，不会隐式启动新线程。
                        slow.run();
                    }
                });
                LocalTcpLab.send(channel, "SLOW");
                LabSupport.await(entered);
                // true 轮：这个任务排在 slow 后面，用来说明单 worker 仍存在队列等待。
                CompletableFuture<Void> otherCallback = new CompletableFuture<>();
                lab.worker().execute(() -> otherCallback.complete(null));
                // 延迟为 0 仍须 EventLoop 有空执行；被 slow 占住时，它不能“另开线程”发 PING。
                lab.io().schedule(() -> {
                    LabSupport.log("心跳", "I/O 发出 PING");
                    channel.writeAndFlush("PING\n");
                }, 0, TimeUnit.MILLISECONDS);
                if (offload) {
                    LabSupport.await(heartbeat);
                    LabSupport.check(!otherCallback.isDone(), "共享 worker 应被慢回调占住");
                    LabSupport.log("结论", "I/O 心跳继续，其他 worker 回调仍在等待");
                } else {
                    try {
                        heartbeat.get(150, TimeUnit.MILLISECONDS);
                        throw new AssertionError("I/O 被占住时心跳不应完成");
                    } catch (TimeoutException expected) {
                        LabSupport.log("结论", "当前观察窗口内，I/O 心跳被慢回调阻塞");
                    }
                }
                // 两轮都必须放行并等待尾部事件，保证实验结束时没有仍被闸门占住的线程。
                release.countDown();
                LabSupport.await(callbackDone);
                LabSupport.await(heartbeat);
                LabSupport.await(otherCallback);
                lab.assertHealthy();
            } finally {
                // 前面的检查即使失败，也放行慢任务，避免清理时再等一个永远不返回的回调。
                release.countDown();
            }
        }
    }
}
