package com.tu.deviceplatform.nettylab.core;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 最小有限序列：状态只在 Channel 的 EventLoop 中读写。 */
public final class TimedSequence {
    public final CompletableFuture<Void> admitted = new CompletableFuture<>();
    public final CompletableFuture<Void> waiting = new CompletableFuture<>();
    public final CompletableFuture<Void> done = new CompletableFuture<>();
    private final Channel channel;
    private final EventLoop loop;
    private ScheduledFuture<?> delayedAi;

    public TimedSequence(Channel channel) {
        this.channel = channel;
        this.loop = channel.eventLoop();
    }

    public void start(long settleMillis) {
        loop.execute(() -> {
            LabSupport.log("序列", "受理；admitted 完成，done 尚未完成");
            admitted.complete(null);
            channel.writeAndFlush("POSITION\n").addListener(write -> {
                if (!write.isSuccess()) {
                    waiting.completeExceptionally(write.cause());
                    done.completeExceptionally(write.cause());
                    return;
                }
                delayedAi = loop.schedule(this::enableAi, settleMillis, TimeUnit.MILLISECONDS);
                waiting.complete(null);
                LabSupport.log("序列", "注册延迟 AI；线程没有 sleep");
            });
        });
    }

    private void enableAi() {
        if (done.isDone()) {
            return;
        }
        channel.writeAndFlush("AI_ON\n").addListener(write -> {
            if (write.isSuccess()) {
                LabSupport.log("序列", "AI_ON 写出，done 完成；不代表识别成功");
                done.complete(null);
            } else {
                done.completeExceptionally(write.cause());
            }
        });
    }

    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        loop.execute(() -> {
            if (delayedAi != null) {
                delayedAi.cancel(false);
            }
            done.completeExceptionally(new CancellationException("STOP 取消未执行 AI"));
            channel.writeAndFlush("STOP\n").addListener(write -> {
                if (write.isSuccess()) {
                    LabSupport.log("序列", "延迟步骤已取消，STOP 已写出");
                    stopped.complete(null);
                } else {
                    stopped.completeExceptionally(write.cause());
                }
            });
        });
        return stopped;
    }

    /** 只在 I/O 线程检查，用于验证取消的是实际定时任务。 */
    public boolean delayedAiCancelled() {
        LabSupport.check(loop.inEventLoop(), "序列状态只能由 EventLoop 读取");
        return delayedAi != null && delayedAi.isCancelled();
    }
}
