package com.tu.deviceplatform.nettylab.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 用最小假资源解释“close 只调用一次，为什么还要与 register 使用同一把锁”。
 *
 * <p>不变量：关闭流程必须收齐已经获准登记的资源，关闭后不能再漏进一个未关闭资源。
 * volatile 只保证 closed 的可见性，不把“检查 closed + 新建并登记资源”合成一个原子操作。
 * useMonitor=true 时两个入口都锁同一个 this，才让这两段操作相互排斥。
 *
 * <p>用法：只由 LifecycleLockDemo 安排好的闸门顺序驱动。false 分支故意重现错误，
 * 不是可复用的无锁实现；锁内等待也只是教学暂停点，不是建议生产代码采用的做法。
 */
public final class LifecycleRegistry implements AutoCloseable {
    // 对照开关：只改变是否在“两个入口”使用同一个对象 monitor。
    private final boolean useMonitor;
    // 正确分支的增删访问受实例锁保护；错误分支靠实验闸门固定交错，不支持任意并发访问。
    private final List<Resource> resources = new ArrayList<>();
    // 可见性与互斥是两件事：读到 false 后，另一线程仍可能立刻改成 true。
    private volatile boolean closed;

    /** @param useMonitor true 演示同锁互斥，false 演示只有 volatile 的漏洞 */
    public LifecycleRegistry(boolean useMonitor) {
        this.useMonitor = useMonitor;
    }

    /**
     * 尝试登记一个假资源。
     * @param checked 检查 closed 通过后通知驱动线程，证明“检查已经发生”
     * @param continueRegistration 驱动线程放行后才继续登记，用于稳定复现检查与使用间的交错
     * @return 已关闭则 false；通过最初检查且完成登记则 true
     */
    public boolean register(CountDownLatch checked, CountDownLatch continueRegistration) {
        if (useMonitor) {
            synchronized (this) {
                return registerInside(checked, continueRegistration);
            }
        }
        return registerInside(checked, continueRegistration);
    }

    /** 必须把检查和登记作为整体理解；只锁 add 或仅将 closed 声明为 volatile 都不等价。 */
    private boolean registerInside(CountDownLatch checked, CountDownLatch continueRegistration) {
        if (closed) {
            LabSupport.log("同步", "已关闭，拒绝登记");
            return false;
        }
        LabSupport.log("同步", "通过 closed 检查，useMonitor=" + useMonitor);
        checked.countDown();
        // 仅为实验注入暂停点：模拟检查通过后线程被抢占，不代表生产代码要在锁内等待。
        LabSupport.gate(continueRegistration);
        resources.add(new Resource());
        LabSupport.log("同步", "登记资源");
        return true;
    }

    /** 与 register 竞争同一个 this；调用次数不是这里需要锁的原因，入口之间的交错才是。 */
    @Override
    public void close() {
        if (useMonitor) {
            synchronized (this) {
                closeInside();
            }
        } else {
            closeInside();
        }
    }

    /** 只能关闭遍历时已在表内的资源；随后才 add 的对象不会被“自动补关”。 */
    private void closeInside() {
        closed = true;
        for (Resource resource : resources) {
            resource.closed = true;
        }
        LabSupport.log("关闭", "收集并关闭现有资源，数量=" + resources.size());
    }

    /** 实验线程已结束后读取；不将 ArrayList 当成并发集合。 */
    public long openResources() {
        return resources.stream().filter(resource -> !resource.closed).count();
    }

    /** 假资源只保留关闭标记，剥离 TCP、协议和异步 close，便于单独理解 monitor。 */
    private static final class Resource {
        private boolean closed;
    }
}
