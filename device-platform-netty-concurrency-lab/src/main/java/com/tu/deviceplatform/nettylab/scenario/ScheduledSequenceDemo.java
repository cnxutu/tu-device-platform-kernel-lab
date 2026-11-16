package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import com.tu.deviceplatform.nettylab.core.TimedSequence;
import io.netty.channel.Channel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 实验 3：用 EventLoop 定时任务表达步骤间等待，并区分“受理”和“执行完成”。
 *
 * <p>第一条序列发 POSITION 后等待 AI，期间发送 PING，再 STOP 取消旧 AI；第二条序列正常完成。
 * 预期：等候期间 PING 可往返，旧 done 异常完成且实际定时句柄被取消，新序列依次回显 POSITION、AI_ON。
 * 这里用队列记录真实回显，不将写完成当作设备确认，也不通过 sleep 等待“应该差不多完成了”。
 *
 * <p>断点：TimedSequence 的 admitted.complete、waiting.complete、enableAi、stop 的取消处。
 * 调试取消路径时增加 lab.delayMillis；正常完成分支仍用 200ms，暂停过久会改变取消时机。
 */
public final class ScheduledSequenceDemo {
    private ScheduledSequenceDemo() {}

    /** 运行取消路径与正常完成路径，日志可对照 TimedSequence 的三个阶段信号。 */
    public static void main(String[] args) throws Exception {
        run();
    }

    /** main／测试线程只驱动和观测；序列内部状态推进始终在 Channel 的 EventLoop 上。 */
    public static void run() throws Exception {
        try (LocalTcpLab lab = new LocalTcpLab()) {
            // 接收者在 I/O 写队列，驱动线程 poll；BlockingQueue 负责跨线程安全传递与有界等待。
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();
            Channel channel = lab.connect("sequence-device", replies::add);
            TimedSequence cancelled = new TimedSequence(channel);
            cancelled.start(LabSupport.DELAY_MILLIS);
            LabSupport.await(cancelled.admitted);
            // 先等延迟任务已登记，才讨论取消；只等 admitted 还不足以证明登记完成。
            LabSupport.await(cancelled.waiting);
            LabSupport.check(!cancelled.done.isDone(), "受理不等于执行完成");
            LocalTcpLab.send(channel, "PING");
            expect(replies, "POSITION");
            expect(replies, "PING");
            LabSupport.log("结论", "序列等待 AI 时，真实 TCP PING 仍可往返");
            // stopped 正常完成与旧 done 异常完成可以同时成立：STOP 成功，旧序列被终止。
            LabSupport.await(cancelled.stop());
            expect(replies, "STOP");
            LabSupport.check(cancelled.done.isCompletedExceptionally(), "旧序列应被取消");
            // 句柄属于 EventLoop，把检查提交回所属线程，再把布尔结果带回驱动线程。
            LabSupport.check(LabSupport.await(lab.io().submit(cancelled::delayedAiCancelled)),
                    "延迟 AI 任务必须取消");

            // 新建实例代表新序列；不复用已经终结的旧 Future。
            TimedSequence completed = new TimedSequence(channel);
            completed.start(200);
            LabSupport.await(completed.done);
            expect(replies, "POSITION");
            expect(replies, "AI_ON");
            LabSupport.check(replies.isEmpty(), "不应有已取消序列的额外指令");
            lab.assertHealthy();
        }
    }

    /** 从按到达顺序保存的真实回显中取下一条；超时取不到或顺序不符均失败。 */
    private static void expect(BlockingQueue<String> replies, String expected) throws Exception {
        LabSupport.check(expected.equals(replies.poll(LabSupport.WAIT_SECONDS, TimeUnit.SECONDS)),
                "期望收到 " + expected);
    }
}
