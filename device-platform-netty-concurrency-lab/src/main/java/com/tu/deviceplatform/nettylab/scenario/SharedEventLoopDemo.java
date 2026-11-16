package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 断点：接收 lambda；观察三个 Channel 对应的线程对象。 */
public final class SharedEventLoopDemo {
    private SharedEventLoopDemo() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        try (LocalTcpLab lab = new LocalTcpLab()) {
            List<Channel> channels = new ArrayList<>();
            List<CompletableFuture<Thread>> replies = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                String device = "device-" + index;
                CompletableFuture<Thread> reply = new CompletableFuture<>();
                replies.add(reply);
                channels.add(lab.connect(device, text -> {
                    LabSupport.check(device.equals(text), "设备消息发生串线");
                    reply.complete(Thread.currentThread());
                }));
            }
            for (int index = 0; index < channels.size(); index++) {
                LocalTcpLab.send(channels.get(index), "device-" + (index + 1));
            }
            Thread owner = LabSupport.await(replies.get(0));
            for (int index = 0; index < channels.size(); index++) {
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
