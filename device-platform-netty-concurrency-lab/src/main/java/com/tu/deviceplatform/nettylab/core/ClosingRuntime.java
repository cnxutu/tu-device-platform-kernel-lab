package com.tu.deviceplatform.nettylab.core;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 单设备关闭教学模型：终止标记由实例锁保护，协议任务由 EventLoop 处理。 */
public final class ClosingRuntime implements AutoCloseable {
    private final LocalTcpLab lab;
    private final Channel channel;
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final AtomicInteger lateActions = new AtomicInteger();
    private boolean closed;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> delayedAi;
    private ScheduledFuture<?> reconnect;

    public ClosingRuntime(LocalTcpLab lab, Channel channel) throws Exception {
        this.lab = lab;
        this.channel = channel;
        LabSupport.await(lab.io().submit(() -> {
            heartbeat = lab.io().scheduleAtFixedRate(() -> channel.writeAndFlush("PING\n"),
                    0, 100, TimeUnit.MILLISECONDS);
            delayedAi = lab.io().schedule(() -> {
                lateActions.incrementAndGet();
                channel.writeAndFlush("AI_ON\n");
            }, LabSupport.DELAY_MILLIS, TimeUnit.MILLISECONDS);
            // 只模拟重连调度入口，避免重新实现生产 Bootstrap 重连流程。
            reconnect = lab.io().schedule(() -> {
                lateActions.incrementAndGet();
                LabSupport.log("重连", "模拟重连任务触发");
            }, LabSupport.DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }));
    }

    public synchronized boolean acceptsNewDevice() {
        return !closed;
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (this) {
            if (closed) {
                return terminated;
            }
            closed = true;
        }
        lab.io().execute(() -> {
            heartbeat.cancel(false);
            delayedAi.cancel(false);
            reconnect.cancel(false);
            LabSupport.log("关闭", "取消心跳、延迟 AI 和重连任务，尝试发送 STOP");
            channel.writeAndFlush("STOP\n").addListener(write -> {
                // 即便 STOP 写失败也继续释放资源；失败通过最终结果传播。
                channel.close().addListener(disconnected -> {
                    LabSupport.log("关闭", "Channel 已关闭，开始停止客户端执行器");
                    lab.shutdownClientAsync().whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            terminated.completeExceptionally(failure);
                        } else if (!write.isSuccess()) {
                            terminated.completeExceptionally(write.cause());
                        } else if (!disconnected.isSuccess()) {
                            terminated.completeExceptionally(disconnected.cause());
                        } else {
                            terminated.complete(null);
                        }
                    });
                });
            });
        });
        return terminated;
    }

    @Override
    public void close() throws Exception {
        CompletableFuture<Void> completion = closeAsync();
        if (!lab.io().inEventLoop() && !lab.worker().inEventLoop()) {
            LabSupport.await(completion);
        } else {
            LabSupport.log("关闭", "来自 I/O 或 worker，仅发起关闭，不等待自己退出");
        }
    }

    /** 调用者已经等待 terminated，此时不会再有 I/O 写这些状态。 */
    public void assertReleased() {
        LabSupport.check(heartbeat.isCancelled() && delayedAi.isCancelled() && reconnect.isCancelled(),
                "所有定时任务必须取消");
        LabSupport.check(!channel.isOpen() && lateActions.get() == 0, "关闭后不应遗留连接或延迟动作");
    }
}
