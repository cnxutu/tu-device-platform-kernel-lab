package com.tu.deviceplatform.nettylab.scenario;

import com.tu.deviceplatform.nettylab.adapter.LocalTcpLab;
import com.tu.deviceplatform.nettylab.core.ClosingRuntime;
import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;

/**
 * 实验 6：普通线程可以等待关闭完成，为什么 worker 回调内不能同样等待？
 *
 * <p>两轮分别从主线程、worker 调用 ClosingRuntime.close。worker 轮必须先返回当前任务，
 * 自己所属的执行器才有机会终止；随后由外部线程等同一个 terminated 结果。
 * 预期：重复关闭共享结果、拒绝新工作、定时任务取消、Channel 关闭，客户端执行器退出。
 *
 * <p>断点：ClosingRuntime.closeAsync 的短同步块、I/O 清理 lambda，以及 close 的线程判断。
 * 观察 returned 与 terminated 的区别；前者只证明 worker 中的 close 已返回，不代表全部资源已释放。
 * 不实际制造自我等待死锁，而通过正确路径的完成检查解释应当怎样避免。
 */
public final class GracefulCloseDemo {
    private GracefulCloseDemo() {}

    /** 先运行外部线程关闭，再运行回调内关闭，比较日志与完成信号。 */
    public static void main(String[] args) throws Exception {
        run();
    }

    /** 每轮单独创建资源，关闭客户端后再由外层夹具关闭模拟设备端。 */
    public static void run() throws Exception {
        compare(false);
        compare(true);
    }

    /** @param insideCallback true 从唯一 worker 关闭；false 从普通实验线程关闭 */
    private static void compare(boolean insideCallback) throws Exception {
        try (LocalTcpLab lab = new LocalTcpLab()) {
            // 本实验观察释放而不是业务响应，因此接收回显时无需做任何业务动作。
            Channel channel = lab.connect("closing-device", text -> {});
            ClosingRuntime runtime = new ClosingRuntime(lab, channel);
            if (insideCallback) {
                // 独立记录 close 是否已从 worker 返回，避免把“已发起”和“全部结束”混为一谈。
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
            // 此时可能只是已发起；重复调用获取同一个终止结果，不会再创建另一轮清理。
            CompletableFuture<Void> first = runtime.closeAsync();
            LabSupport.check(first == runtime.closeAsync(), "重复关闭必须复用完成结果");
            // 等待放在外部线程；在 worker 内等这个结果会阻止 worker 自己终止。
            LabSupport.await(first);
            LabSupport.check(!runtime.acceptsNewDevice(), "关闭后必须拒绝新设备");
            runtime.assertReleased();
            lab.assertHealthy();
            LabSupport.log("结论", "关闭完成，insideCallback=" + insideCallback);
        }
    }
}
