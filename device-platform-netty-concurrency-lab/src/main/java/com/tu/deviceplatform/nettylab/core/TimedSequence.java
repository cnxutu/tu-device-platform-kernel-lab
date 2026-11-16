package com.tu.deviceplatform.nettylab.core;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * “先转向 POSITION，稍后开启 AI”的最小异步序列，供 ScheduledSequenceDemo 阅读和调试。
 *
 * <p>用法：为一条已连接 Channel 创建一个实例，start 一次；调用线程可等待 admitted、waiting、
 * done 观察不同阶段，或在 waiting 后、AI 到期前调用 stop。每次新序列都创建新实例，
 * 不支持复用、重复 start 或任意时刻 STOP 抢占的完整生产状态机。
 *
 * <p>状态归属：delayedAi 和序列推进由该 Channel 的 EventLoop 串行管理；外部通过 execute
 * 提交意图，通过线程安全的 CompletableFuture 接收结果。这里只传递任务，没有新增序列线程，
 * 也不需要给每个推进步骤加 synchronized。定时等待期间 EventLoop 仍能处理其他 I/O。
 */
public final class TimedSequence {
    /** EventLoop 已开始受理本次 start；此时连 POSITION 写出都还没有完成。 */
    public final CompletableFuture<Void> admitted = new CompletableFuture<>();
    /** POSITION 本地写成功，延迟 AI 已登记；供实验可靠地安排“等候期间的 PING／STOP”。 */
    public final CompletableFuture<Void> waiting = new CompletableFuture<>();
    /** AI_ON 本地写成功才正常完成；POSITION／AI 写失败或 STOP 则异常完成，不表示设备动作完成。 */
    public final CompletableFuture<Void> done = new CompletableFuture<>();
    private final Channel channel;
    private final EventLoop loop;
    // 定时任务句柄：用于取消尚未执行的步骤，不是承载这一步的新线程。
    private ScheduledFuture<?> delayedAi;

    /** 将序列绑定到 Channel 已有的 EventLoop，避免创建第二个互不协调的调度器。 */
    public TimedSequence(Channel channel) {
        this.channel = channel;
        this.loop = channel.eventLoop();
    }

    /**
     * 提交启动意图后立即返回；调用者通过三个 Future 区分进度。
     * @param settleMillis POSITION 写成功后至少等待多久再安排 AI；到期不保证精确立即执行
     */
    public void start(long settleMillis) {
        loop.execute(() -> {
            LabSupport.log("序列", "受理；admitted 完成，done 尚未完成");
            admitted.complete(null);
            // 写完成监听器继续推进；在 EventLoop 内 get 写结果会妨碍写操作本身完成。
            channel.writeAndFlush("POSITION\n").addListener(write -> {
                if (!write.isSuccess()) {
                    waiting.completeExceptionally(write.cause());
                    done.completeExceptionally(write.cause());
                    return;
                }
                // schedule 注册到 EventLoop 的定时队列后就返回，不像 sleep 那样占住当前线程。
                delayedAi = loop.schedule(this::enableAi, settleMillis, TimeUnit.MILLISECONDS);
                waiting.complete(null);
                LabSupport.log("序列", "注册延迟 AI；线程没有 sleep");
            });
        });
    }

    /** 定时器到期后仍在同一个 EventLoop 推进；done 检查避免已终结的序列再写 AI。 */
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

    /**
     * 在实验约定的 waiting 之后、AI 执行前调用：取消延迟任务，将旧序列终结，再发 STOP。
     * 返回值表示 STOP 本地写出结果，与旧序列的 done 是两个不同承诺。
     * cancel(false) 不打断已经执行的任务，也不能撤回此前已经写出的设备指令。
     */
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        loop.execute(() -> {
            if (delayedAi != null) {
                delayedAi.cancel(false);
            }
            // 旧序列失败不等于 STOP 失败：done 和 stopped 的等待者分别得到各自结果。
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
