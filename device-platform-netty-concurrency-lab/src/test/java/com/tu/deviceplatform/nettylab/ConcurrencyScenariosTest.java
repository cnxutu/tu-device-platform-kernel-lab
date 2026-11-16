package com.tu.deviceplatform.nettylab;

import com.tu.deviceplatform.nettylab.scenario.CallbackOffloadDemo;
import com.tu.deviceplatform.nettylab.scenario.GracefulCloseDemo;
import com.tu.deviceplatform.nettylab.scenario.LifecycleLockDemo;
import com.tu.deviceplatform.nettylab.scenario.ScheduledSequenceDemo;
import com.tu.deviceplatform.nettylab.scenario.SharedEventLoopDemo;
import com.tu.deviceplatform.nettylab.scenario.StaleCallbackDemo;
import org.junit.jupiter.api.Test;

/** 直接执行 main 使用的同一实现；断言失败和异步失败必须导致测试失败。 */
class ConcurrencyScenariosTest {
    @Test
    void channelsShareIoWithoutSharingIdentity() throws Exception {
        SharedEventLoopDemo.run();
    }

    @Test
    void slowCallbackSeparatesIoAndWorkerProgress() throws Exception {
        CallbackOffloadDemo.run();
    }

    @Test
    void delayedSequenceAllowsHeartbeatAndCancellation() throws Exception {
        ScheduledSequenceDemo.run();
    }

    @Test
    void singleCloseStillNeedsMutualExclusionWithRegistration() throws Exception {
        LifecycleLockDemo.run();
    }

    @Test
    void replacementDiscardsQueuedOldChannelCallbacks() throws Exception {
        StaleCallbackDemo.run();
    }

    @Test
    void closeReleasesResourcesWithoutWaitingForOwnWorker() throws Exception {
        GracefulCloseDemo.run();
    }
}
