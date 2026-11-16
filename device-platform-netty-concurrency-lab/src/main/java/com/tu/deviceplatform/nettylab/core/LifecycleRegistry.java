package com.tu.deviceplatform.nettylab.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** 教学模型。无锁模式只供重现指定交错，不是可复用的生产实现。 */
public final class LifecycleRegistry implements AutoCloseable {
    private final boolean useMonitor;
    private final List<Resource> resources = new ArrayList<>();
    private volatile boolean closed;

    public LifecycleRegistry(boolean useMonitor) {
        this.useMonitor = useMonitor;
    }

    public boolean register(CountDownLatch checked, CountDownLatch continueRegistration) {
        if (useMonitor) {
            synchronized (this) {
                return registerInside(checked, continueRegistration);
            }
        }
        return registerInside(checked, continueRegistration);
    }

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

    private static final class Resource {
        private boolean closed;
    }
}
