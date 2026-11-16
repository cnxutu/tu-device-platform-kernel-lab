package com.tu.deviceplatform.nettylab.adapter;

import com.tu.deviceplatform.nettylab.core.LabSupport;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 六个实验共用的本机 TCP 夹具：同时启动一个回显设备端和一组适配器客户端。
 *
 * <p>读法：先看构造器如何启动设备，再看 {@link #connect} 如何创建客户端，最后看
 * {@link #installTextCodec} 如何把 TCP 字节流变成一行文本。设备收到什么就回什么，
 * 因而实验可以把精力放在线程和状态上，不需要理解真实设备协议。
 *
 * <p>常见服务端用法：ServerBootstrap → group(接入组, I/O组) → channel(监听实现)
 * → childHandler(接入连接的处理链) → bind(监听地址)。
 * 常见客户端用法：Bootstrap → group(I/O组) → channel(Socket实现)
 * → handler(本连接的处理链) → connect(目标地址)。这两条配置链可直接对照下面的构造器与 connect。
 *
 * <p>线程分工：sim-accept 接入连接，sim-device-io 处理设备侧读写；adapter-io
 * 是所有客户端 Channel 共用的单线程 EventLoop；adapter-worker 是手动卸载回调的
 * 单线程执行器，不监听 Socket，也不会自动接管 receiver。每次 new 都有独立资源。
 *
 * <p>用法：在 main／测试线程中通过 try-with-resources 创建、connect、send、等待结果，
 * 离开作用域时关闭全部资源。构造、connect、send、close 都有阻塞等待，不能放进本夹具的
 * I/O 或 worker 回调中；需要从回调发起客户端关闭时用 {@link #shutdownClientAsync()}。
 */
public final class LocalTcpLab implements AutoCloseable {
    // Netty NioEventLoopGroup：管理一组基于 Java NIO 的事件循环，负责处理连接事件、读写和已提交任务。
    // new NioEventLoopGroup(1, ...) 的 1 是“这个组有几个 EventLoop”，不是组数或设备数。
    // 不传线程数或传 0 会采用 Netty 默认值，不表示只有一条线程；学习实验显式用 1，便于观察顺序。
    // 本类分别创建接入组、设备 I/O 组、客户端 I/O 组三个实例，是为了在一个进程里同时演示服务端和客户端。
    // DefaultThreadFactory 是 Netty 线程工厂；这里指定名称前缀，便于从日志辨认线程属于哪一组。
    // acceptor 在服务端承担通常称为 boss 的角色：接受新连接，把接入后的 Channel 注册到 deviceIo。
    private final NioEventLoopGroup acceptor =
            new NioEventLoopGroup(1, new DefaultThreadFactory("sim-accept"));
    // 服务端通常称为 worker 的 I/O 组：处理已接入 SocketChannel 的读写，和适配器客户端隔离。
    // 注意这里的“服务端 worker 组”仍是 I/O 线程，不是下面专门卸载慢回调的 adapter-worker。
    private final NioEventLoopGroup deviceIo =
            new NioEventLoopGroup(1, new DefaultThreadFactory("sim-device-io"));
    // 参数 1 表示整个客户端组只有一个 EventLoop，三个连接也不会创建三条 I/O 线程。
    private final NioEventLoopGroup clientIo =
            new NioEventLoopGroup(1, new DefaultThreadFactory("adapter-io"));
    // Netty DefaultEventExecutorGroup：普通任务执行器组，不负责 NIO Socket 读写。
    // 本类用它承接显式 execute 提交的慢回调；参数 1 表示只有一个执行器，回调仍串行排队。
    // 它不是创建 TCP 客户端／服务端的必选组件；只有需要卸载任务时才使用。
    private final DefaultEventExecutorGroup callbacks =
            new DefaultEventExecutorGroup(1, new DefaultThreadFactory("adapter-worker"));
    // 初始化回调会从不同端的 I/O 线程登记 Channel；清理线程还要遍历，故使用并发安全列表。
    // 此列表仅保存少量实验资源，不代表连接池或设备注册表。
    private final List<Channel> channels = new CopyOnWriteArrayList<>();
    // 将 handler 内的异步异常带回测试线程；正常情况下保持未完成，不能直接等待它。
    private final CompletableFuture<Void> handlerFailure = new CompletableFuture<>();
    // 监听 Channel 只接受连接；它与每条已建立连接的 SocketChannel 是不同对象。
    private Channel listener;

    /** 启动回显服务并等待 bind 成功；端口 0 由操作系统分配，避免实验占用固定设备端口。 */
    public LocalTcpLab() throws Exception {
        try {
            // Netty ServerBootstrap：TCP 服务端的启动配置组件，组织线程组、Channel 类型和处理器，
            // 最后用 bind 开始监听；它自身不是监听 Socket，也不是一条工作线程。
            // group(parentGroup, childGroup) 指定两种角色：parent 接受连接，child 处理已接入连接的 I/O。
            // 两组是常见的职责分离方式，并非框架强制；group(group) 也可让两个角色共用一组。
            ChannelFuture binding = new ServerBootstrap().group(acceptor, deviceIo)
                    // Netty NioServerSocketChannel：基于 Java NIO 的 TCP 监听 Channel 类型。
                    // channel(...) 指定由 Netty 创建的实现类；客户端则使用 NioSocketChannel。
                    .channel(NioServerSocketChannel.class)
                    // Netty childHandler：给“每条接入后的连接”配置处理链；handler 则用于监听 Channel。
                    // ChannelInitializer 是连接初始化组件；在 initChannel 中组装该连接自己的 Pipeline。
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            // 每个新连接安装自己的一条 Pipeline 和 handler，不共享设备身份。
                            channels.add(channel);
                            installTextCodec(channel.pipeline());
                            // Netty SimpleChannelInboundHandler<String>：接收入站消息的处理器基类。
                            // 前面的解码器已将消息转为 String；这里只需实现收到一行后的处理逻辑。
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext context, String text) {
                                    // 分帧器已去掉换行；回显时补回换行，客户端才能识别完整一帧。
                                    context.writeAndFlush(text + "\n");
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                    handlerFailure.completeExceptionally(cause);
                                    context.close();
                                }
                            });
                        }
                    // Netty bind：异步绑定监听地址；这里用 loopback 和动态端口，仅供本机实验。
                    }).bind("127.0.0.1", 0);
            // Netty ChannelFuture：一次 Channel 异步操作的结果，可检查成功／失败或注册完成监听器。
            // 它不是新线程；这里由外部驱动线程等待，I/O 回调内通常用 addListener 接续处理。
            // bind 返回不等于已监听成功；显式等待其 Future 后再取实际地址。
            LabSupport.await(binding);
            listener = binding.channel();
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    /**
     * 建立一条独立的客户端 TCP 连接，返回前等待连接成功。
     *
     * @param device 仅用于实验日志的模拟设备名，不参与路由或鉴权
     * @param receiver 收到完整文本行时调用；默认运行在 adapter-io，若需要卸载须显式提交给 worker
     * @return 本次连接的 Channel；其 eventLoop 是观察线程归属的入口
     */
    public Channel connect(String device, Consumer<String> receiver) throws Exception {
        // Netty Bootstrap：TCP 客户端的启动配置组件；与服务端 bind 相对，通过 connect 主动建立连接。
        // 客户端没有接受连接的 boss 角色，通常配置一个 I/O 组即可，多条连接可以共享它。
        // NioSocketChannel 是已连接 TCP Socket 的 NIO 实现类型，Channel 才是每次建成后的连接对象。
        ChannelFuture connecting = new Bootstrap().group(clientIo).channel(NioSocketChannel.class)
                // Netty ChannelOption：配置 Channel 的选项；这里限制建立 TCP 连接的等待时间为 3000ms，
                // 它不是业务请求超时、读超时或心跳间隔。服务端 option 配监听 Channel，childOption 配接入连接。
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                // 客户端 handler 配自己的连接处理链，不使用服务端的 childHandler。
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socket) {
                        channels.add(socket);
                        installTextCodec(socket.pipeline());
                        socket.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext context, String text) {
                                // Netty ChannelHandlerContext：当前 handler 在 Pipeline 中的上下文，
                                // 可访问 Channel、向后传播入站事件，或从当前位置发起写出、关闭等操作。
                                LabSupport.log(device, "收到 " + text + " channel=" + socket.id().asShortText());
                                // 普通 Java 方法调用不会切线程；在此直接等待 RPC 会占住 I/O。
                                receiver.accept(text);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                handlerFailure.completeExceptionally(cause);
                                context.close();
                            }
                        });
                    }
                // Netty connect：异步连接目标服务，返回本次建连的 ChannelFuture；成功后再开始实验收发。
                }).connect((InetSocketAddress) listener.localAddress());
        LabSupport.await(connecting);
        Channel channel = connecting.channel();
        LabSupport.log(device, "已建连 channel=" + channel.id().asShortText());
        return channel;
    }

    /**
     * 给两端安装相同的最小文本协议：UTF-8 + 换行结束的一帧，最大帧长 1024 字节。
     * 入站依次分帧、解码为 String、交给接收 handler；出站反向经过 StringEncoder。
     * TCP 本身没有消息边界，一次 write 不保证对应一次 read，故分帧器不能省略。
     */
    private static void installTextCodec(ChannelPipeline pipeline) {
        // Netty ChannelPipeline：每条 Channel 的处理器链，按方向把事件交给分帧器、编解码器和业务 handler。
        // LineBasedFrameDecoder 是入站分帧组件：按换行切消息，解决 TCP 没有消息边界的问题。
        pipeline.addLast(new LineBasedFrameDecoder(1024));
        // StringDecoder 是入站解码组件：将完整帧的 ByteBuf 字节转成 UTF-8 String。
        pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
        // StringEncoder 是出站编码组件：将 String 编成 UTF-8 字节；方向与入站处理相反。
        // 这两个字符串编解码器不会自动补换行，所以发送方和回显方要明确添加结束符。
        pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
    }

    /**
     * 实验驱动线程使用的发送助手：补换行并等待本地写操作成功。
     * 此返回只证明写操作完成；需要证明设备已收到时，还要等待 receiver 的回显事件。
     * I/O 回调内应直接 writeAndFlush + listener，不能在这里等待同一 EventLoop。
     */
    public static void send(Channel channel, String text) throws Exception {
        // Netty Channel.writeAndFlush：提交出站消息并请求刷新到底层传输，返回本地写操作的 Future。
        LabSupport.await(channel.writeAndFlush(text + "\n"));
    }

    /**
     * Netty EventLoop 是组内的单线程事件循环，可处理 I/O、普通任务和定时任务。
     * 此方法返回唯一的客户端 EventLoop；next() 在单线程组中始终取到同一个执行器。
     * 多线程组中 next() 会选择组成员，不能据此认定它就是某条连接的所有者；应使用 channel.eventLoop()。
     */
    public EventLoop io() {
        return clientIo.next();
    }

    /**
     * Netty EventExecutor 是执行任务的组件接口；本方法返回不处理 Socket I/O 的唯一回调执行器。
     * execute 提交后返回，submit 还返回任务的完成结果；EventLoop 也属于 EventExecutor，但额外承担 I/O。
     */
    public EventExecutor worker() {
        return callbacks.next();
    }

    /** 在已经等到预期事件后调用，检查 handler 异常；它不是“所有异步任务都已结束”的屏障。 */
    public void assertHealthy() throws Exception {
        if (handlerFailure.isDone()) {
            LabSupport.await(handlerFailure);
        }
    }

    /**
     * 发起两个客户端执行器的异步退出，返回二者均终止后的结果；可从 I/O 或 worker 调用。
     * 调用本身不等待；调用者也不能在这两个执行器内部 get 返回结果，否则会等自己退出。
     * 先关闭业务 Channel 的顺序由 ClosingRuntime 演示；模拟设备端仍由最外层 close 清理。
     */
    public CompletableFuture<Void> shutdownClientAsync() {
        CompletableFuture<Void> ioStopped = new CompletableFuture<>();
        CompletableFuture<Void> workerStopped = new CompletableFuture<>();
        // Netty shutdownGracefully：通知组内执行器完成退出流程，并返回可观察终止结果的 Future。
        // 0 是 quiet period，2 秒是 Netty 优雅退出参数，不是强行中断阻塞任务的保证。
        clientIo.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(result -> {
            if (result.isSuccess()) {
                ioStopped.complete(null);
            } else {
                ioStopped.completeExceptionally(result.cause());
            }
        });
        callbacks.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(result -> {
            if (result.isSuccess()) {
                workerStopped.complete(null);
            } else {
                workerStopped.completeExceptionally(result.cause());
            }
        });
        // allOf 只组合完成信号，不另开一条等待线程；任一退出失败会使组合结果异常完成。
        return CompletableFuture.allOf(ioStopped, workerStopped);
    }

    /** 最外层实验线程的兜底清理：先关监听和连接，再等待四组执行器退出并检查遗漏。 */
    @Override
    public void close() {
        try {
            if (listener != null) {
                closeChannel(listener);
            }
            for (Channel channel : channels) {
                closeChannel(channel);
            }
        } finally {
            Future<?> ioClosed = clientIo.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            Future<?> workerClosed = callbacks.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            Future<?> deviceClosed = deviceIo.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            Future<?> acceptorClosed = acceptor.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            awaitCleanup(ioClosed);
            awaitCleanup(workerClosed);
            awaitCleanup(deviceClosed);
            awaitCleanup(acceptorClosed);
        }
        LabSupport.check(channels.stream().noneMatch(Channel::isOpen), "仍有 TCP Channel 未关闭");
        LabSupport.check(clientIo.isTerminated() && callbacks.isTerminated()
                && deviceIo.isTerminated() && acceptor.isTerminated(), "实验执行器未退出");
        LabSupport.log("资源", "TCP 与客户端／模拟设备端执行器均已退出");
    }

    /** close() 发起关闭，closeFuture() 观察既有关闭；已关闭的连接无需再向退出中的 I/O 提交任务。 */
    private static void closeChannel(Channel channel) {
        // 关闭实验已停止客户端执行器；已关闭的 Channel 应等待既有结果，不能再提交 close 任务。
        if (channel.isOpen()) {
            awaitCleanup(channel.close());
        } else {
            awaitCleanup(channel.closeFuture());
        }
    }

    /** 清理仍有等待上限，并核对成功状态；await 返回只表示“结束”，不一定“成功”。 */
    private static void awaitCleanup(Future<?> future) {
        LabSupport.check(future.awaitUninterruptibly(LabSupport.WAIT_SECONDS, TimeUnit.SECONDS),
                "资源释放超时");
        if (!future.isSuccess()) {
            throw new IllegalStateException("资源释放失败", future.cause());
        }
    }
}
