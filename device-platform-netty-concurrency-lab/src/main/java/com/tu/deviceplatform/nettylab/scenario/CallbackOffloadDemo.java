package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 有意卡住回调，用心跳探针观察 I/O 和 worker 的相互影响。 */
public final class CallbackOffloadDemo {
    private CallbackOffloadDemo() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        compare(false);
        compare(true);
    }

    private static void compare(boolean offload) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> callbackDone = new CompletableFuture<>();
        CompletableFuture<Void> heartbeat = new CompletableFuture<>();
        try (LocalTcpLab lab = new LocalTcpLab()) {
            try {
                Channel channel = lab.connect("slow-device", text -> {
                    if ("PING".equals(text)) {
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
                        lab.worker().execute(slow);
                    } else {
                        slow.run();
                    }
                });
                LocalTcpLab.send(channel, "SLOW");
                LabSupport.await(entered);
                CompletableFuture<Void> otherCallback = new CompletableFuture<>();
                lab.worker().execute(() -> otherCallback.complete(null));
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
                release.countDown();
                LabSupport.await(callbackDone);
                LabSupport.await(heartbeat);
                LabSupport.await(otherCallback);
                lab.assertHealthy();
            } finally {
                release.countDown();
            }
        }
    }
}
