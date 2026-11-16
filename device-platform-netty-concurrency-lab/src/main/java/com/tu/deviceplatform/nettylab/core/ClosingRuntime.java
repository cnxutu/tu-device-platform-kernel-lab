package com.tu.deviceplatform.nettylab.core;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单设备关闭教学模型：短锁负责阻止重复发起，EventLoop 负责有序释放，Future 负责通知完成。
 *
 * <p>阅读顺序：构造器登记三类任务 → closeAsync 设置关闭门闩 → I/O 取消定时任务并发 STOP
 * → 关闭 Channel → 停止客户端 I/O／worker → terminated 完成。
 * “发起关闭”和“关闭完成”之间有异步过程，所以需要两个不同的观测点。
 *
 * <p>用法：在普通实验线程创建；从普通线程 close 会等待结果，从 I/O／worker close 则只发起。
 * 外部驱动线程等待 closeAsync 的结果后调用 assertReleased；最后由 LocalTcpLab.close
 * 关闭模拟设备端。实验期间不能提前单独关闭 lab 的执行器。
 */
public final class ClosingRuntime implements AutoCloseable {
    private final LocalTcpLab lab;
    private final Channel channel;
    // 所有调用者观察同一个结果；closed=true 不能替代它，因为执行器此时可能尚未退出。
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    // 定时任务若已触发则加一，最终检查可发现“取消得太晚”；原子计数只用于跨线程观测。
    private final AtomicInteger lateActions = new AtomicInteger();
    // 仅在 synchronized(this) 内访问，不需要 volatile；定时任务由 I/O 取消来终止。
    private boolean closed;
    // 三个句柄在 I/O 中登记、取消；结束后由驱动线程在完成信号之后检查。
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> delayedAi;
    private ScheduledFuture<?> reconnect;

    /** 在唯一的客户端 EventLoop 登记心跳和两个待取消任务；返回时确保句柄已初始化。 */
    public ClosingRuntime(LocalTcpLab lab, Channel channel) throws Exception {
        this.lab = lab;
        this.channel = channel;
        LabSupport.await(lab.io().submit(() -> {
            // 固定频率心跳和 Channel I/O 使用同一线程；每 100ms 调度一次，不是精确时钟承诺。
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

    /** 与关闭门闩使用同一实例锁，演示关闭后拒绝新工作；本类没有实现设备注册表。 */
    public synchronized boolean acceptsNewDevice() {
        return !closed;
    }

    /**
     * 从任意调用线程发起一次关闭；重复调用复用同一 terminated，不重复提交清理任务。
     * 同步块只决定“谁发起”，锁内不等 I/O、Channel 或执行器终止。
     * @return STOP、连接关闭与客户端执行器退出后的结果；失败也会在释放路径走完后报告
     */
    public CompletableFuture<Void> closeAsync() {
        synchronized (this) {
            if (closed) {
                return terminated;
            }
            closed = true;
        }
        // 离开实例锁后再投递：异步等待不占住锁，协议操作仍由 I/O 串行执行。
        lab.io().execute(() -> {
            heartbeat.cancel(false);
            delayedAi.cancel(false);
            reconnect.cancel(false);
            LabSupport.log("关闭", "取消心跳、延迟 AI 和重连任务，尝试发送 STOP");
            // listener 串联顺序，不能用三行“提交调用”就推定三件异步工作已经依次完成。
            channel.writeAndFlush("STOP\n").addListener(write -> {
                // 即便 STOP 写失败也继续释放资源；失败通过最终结果传播。
                channel.close().addListener(disconnected -> {
                    LabSupport.log("关闭", "Channel 已关闭，开始停止客户端执行器");
                    // 必须在 Channel 清理后才停执行器；通知回调中不再提交新的 I/O 工作。
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

    /**
     * 普通线程可在锁外等待；I/O／worker 必须返回让自己继续执行、退出。
     * 若 worker 在这里等待 terminated，而 terminated 又要求 worker 终止，就形成自我等待。
     */
    @Override
    public void close() throws Exception {
        CompletableFuture<Void> completion = closeAsync();
        if (!lab.io().inEventLoop() && !lab.worker().inEventLoop()) {
            LabSupport.await(completion);
        } else {
            LabSupport.log("关闭", "来自 I/O 或 worker，仅发起关闭，不等待自己退出");
        }
    }

    /**
     * 前置条件：驱动线程已经成功等待 terminated。此时状态不再变化，才能安全检查句柄与 Channel。
     * canceled 验证任务被取消；lateActions==0 还要求它们在本次演示中没有提前触发。
     */
    public void assertReleased() {
        LabSupport.check(heartbeat.isCancelled() && delayedAi.isCancelled() && reconnect.isCancelled(),
                "所有定时任务必须取消");
        LabSupport.check(!channel.isOpen() && lateActions.get() == 0, "关闭后不应遗留连接或延迟动作");
    }
}
