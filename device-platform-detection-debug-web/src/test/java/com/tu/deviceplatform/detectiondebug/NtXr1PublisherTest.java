package com.tu.deviceplatform.detectiondebug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.detectiondebug.adapter.NtXr1Publisher;
import com.tu.deviceplatform.detectiondebug.core.NtXr1Payload;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Wire-level MQTT smoke with an in-process broker stub, without an external broker or device. */
class NtXr1PublisherTest {
    @Test void publishesExactNonRetainedHeartbeatAndPointOnlyWhenAsked() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket broker=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            NtXr1Publisher sender=new NtXr1Publisher("tcp://127.0.0.1:"+broker.getLocalPort(),"", "")) {
            broker.setSoTimeout(3000);
            Future<List<byte[]>> frames=executor.submit(()->{
                List<byte[]> messages=new ArrayList<>();
                try(Socket client=broker.accept()) {
                    client.setSoTimeout(3000); InputStream input=client.getInputStream();
                    byte[] connect=read(input); assertEquals(0x10,connect[0]&255);
                    client.getOutputStream().write(new byte[]{0x20,2,0,0}); client.getOutputStream().flush();
                    messages.add(read(input)); messages.add(read(input)); return messages;
                }
            });
            Thread.sleep(100);assertFalse(frames.isDone());
            sender.publish(NtXr1Payload.topic("SIM-DETECT-01",true),NtXr1Payload.heartbeat("SIM-DETECT-01",1000));
            sender.publish(NtXr1Payload.topic("SIM-DETECT-01",false),NtXr1Payload.locate("SIM-DETECT-01","SIM-UAV-01",
                    new ObjectMapper().readTree("{\"longitude\":120.5,\"latitude\":30.5,\"altitude\":123.5}"),2000));
            List<byte[]> sent=frames.get(4,TimeUnit.SECONDS);
            for(byte[] frame:sent)assertEquals(0x30,frame[0]&255); // QoS 0, retain=false.
            byte[] point=sent.get(1);int topicLength=((point[1]&255)<<8)|(point[2]&255);
            assertEquals("/server/xmrd1/api/LocatedResult/SIM-DETECT-01",new String(point,3,topicLength,StandardCharsets.UTF_8));
            assertEquals(123.5,new ObjectMapper().readTree(Arrays.copyOfRange(point,3+topicLength,point.length)).path("dronePos").get(0).path("uavAltitude").asDouble());
        } finally {executor.shutdownNow();}
    }
    private static byte[] read(InputStream input) throws IOException {
        int type=input.read();if(type<0)throw new EOFException();int size=0,multiplier=1,part;
        do{part=input.read();if(part<0)throw new EOFException();size+=(part&127)*multiplier;multiplier*=128;}while((part&128)!=0);
        byte[] packet=new byte[size+1];packet[0]=(byte)type;new DataInputStream(input).readFully(packet,1,size);return packet;
    }
}
