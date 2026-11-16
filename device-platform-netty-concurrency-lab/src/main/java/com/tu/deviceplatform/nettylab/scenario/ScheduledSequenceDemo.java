package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import com.tu.deviceplatform.nettylab.core.TimedSequence;
import io.netty.channel.Channel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class ScheduledSequenceDemo {
    private ScheduledSequenceDemo() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        try (LocalTcpLab lab = new LocalTcpLab()) {
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();
            Channel channel = lab.connect("sequence-device", replies::add);
            TimedSequence cancelled = new TimedSequence(channel);
            cancelled.start(LabSupport.DELAY_MILLIS);
            LabSupport.await(cancelled.admitted);
            LabSupport.await(cancelled.waiting);
            LabSupport.check(!cancelled.done.isDone(), "受理不等于执行完成");
            LocalTcpLab.send(channel, "PING");
            expect(replies, "POSITION");
            expect(replies, "PING");
            LabSupport.log("结论", "序列等待 AI 时，真实 TCP PING 仍可往返");
            LabSupport.await(cancelled.stop());
            expect(replies, "STOP");
            LabSupport.check(cancelled.done.isCompletedExceptionally(), "旧序列应被取消");
            LabSupport.check(LabSupport.await(lab.io().submit(cancelled::delayedAiCancelled)),
                    "延迟 AI 任务必须取消");

            TimedSequence completed = new TimedSequence(channel);
            completed.start(200);
            LabSupport.await(completed.done);
            expect(replies, "POSITION");
            expect(replies, "AI_ON");
            LabSupport.check(replies.isEmpty(), "不应有已取消序列的额外指令");
            lab.assertHealthy();
        }
    }

    private static void expect(BlockingQueue<String> replies, String expected) throws Exception {
        LabSupport.check(expected.equals(replies.poll(LabSupport.WAIT_SECONDS, TimeUnit.SECONDS)),
                "期望收到 " + expected);
    }
}
