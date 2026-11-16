package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.ClosingRuntime;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;

public final class GracefulCloseDemo {
    private GracefulCloseDemo() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        compare(false);
        compare(true);
    }

    private static void compare(boolean insideCallback) throws Exception {
        try (LocalTcpLab lab = new LocalTcpLab()) {
            Channel channel = lab.connect("closing-device", text -> {});
            ClosingRuntime runtime = new ClosingRuntime(lab, channel);
            if (insideCallback) {
                CompletableFuture<Void> returned = new CompletableFuture<>();
                lab.worker().execute(() -> {
                    try {
                        LabSupport.log("worker", "在回调里调用 close");
                        runtime.close();
                        returned.complete(null);
                    } catch (Exception failure) {
                        returned.completeExceptionally(failure);
                    }
                });
                LabSupport.await(returned);
            } else {
                LabSupport.log("主线程", "普通线程 close，等待完整退出");
                runtime.close();
            }
            CompletableFuture<Void> first = runtime.closeAsync();
            LabSupport.check(first == runtime.closeAsync(), "重复关闭必须复用完成结果");
            LabSupport.await(first);
            LabSupport.check(!runtime.acceptsNewDevice(), "关闭后必须拒绝新设备");
            runtime.assertReleased();
            lab.assertHealthy();
            LabSupport.log("结论", "关闭完成，insideCallback=" + insideCallback);
        }
    }
}
