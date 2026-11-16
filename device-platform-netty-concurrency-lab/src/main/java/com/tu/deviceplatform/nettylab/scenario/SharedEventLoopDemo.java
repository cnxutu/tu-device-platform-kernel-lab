package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 实验 1：三个设备连接是否一定需要三条 I/O 线程？
 *
 * <p>准备三条独立 TCP Channel，各发一条带设备名的文本；接收时同时记录文本与当前线程。
 * 预期：消息不串设备，Channel 对象不同，但接收线程及 EventLoop 对象相同。
 * 理解重点是“连接身份”和“调度线程”分离，同线程不等于共享同一份设备状态。
 *
 * <p>断点：receiver 中 reply.complete 一行；查看 device、text、当前线程、Channel ID。
 * 本实验只证明 LocalTcpLab 配置的一个客户端 EventLoop，不代表 Netty 永远只有一个线程。
 */
public final class SharedEventLoopDemo {
    private SharedEventLoopDemo() {}

    /** IDEA 直接 Run／Debug 的入口；与 JUnit 调用同一个 run，无需网关或实机。 */
    public static void main(String[] args) throws Exception {
        run();
    }

    /** 建连 → 发身份文本 → 等待全部回显 → 比较线程和 Channel → 自动释放资源。 */
    public static void run() throws Exception {
        try (LocalTcpLab lab = new LocalTcpLab()) {
            List<Channel> channels = new ArrayList<>();
            List<CompletableFuture<Thread>> replies = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                String device = "device-" + index;
                // Future 是接收线程向驱动线程交付“观测结果”的容器，本身不会创建新线程。
                CompletableFuture<Thread> reply = new CompletableFuture<>();
                replies.add(reply);
                // 每次循环的 device、reply 被该连接的 lambda 捕获，线程共享不妨碍身份分开保存。
                channels.add(lab.connect(device, text -> {
                    LabSupport.check(device.equals(text), "设备消息发生串线");
                    reply.complete(Thread.currentThread());
                }));
            }
            for (int index = 0; index < channels.size(); index++) {
                LocalTcpLab.send(channels.get(index), "device-" + (index + 1));
            }
            // 等接收确实发生后再核对；只等 send 写成功不足以证明接收线程是谁。
            Thread owner = LabSupport.await(replies.get(0));
            for (int index = 0; index < channels.size(); index++) {
                // 比较对象身份，避免仅靠相似的线程名得出共享线程的结论。
                LabSupport.check(LabSupport.await(replies.get(index)) == owner, "未共享 I/O 线程");
                LabSupport.check(channels.get(index).eventLoop() == lab.io(), "EventLoop 不相同");
            }
            LabSupport.check(channels.stream().distinct().count() == 3, "应有三个独立 Channel");
            lab.assertHealthy();
            LabSupport.log("结论", "三个客户端 Channel 共享 " + owner.getName()
                    + "；sim-* 为独立模拟设备端线程");
        }
    }
}
