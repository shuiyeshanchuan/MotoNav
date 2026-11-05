package com.example.motonav;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/** Synthetic transport fixtures, not a device throughput or accuracy claim. */
public final class ContinuousImuProtocolTest {
    private static int checks;
    static void check(boolean pass,String text) { checks++;if(!pass)throw new AssertionError(text); }
    static void invalid(Runnable task,String text) {
        try { task.run();throw new AssertionError(text); }catch(IllegalArgumentException expected) { checks++; }
    }
    static byte[] body(long session,long boot,long first,int count,long next,long oldest,long overflow,long missed,long errors,int flags) {
        ByteBuffer b=ByteBuffer.allocate(52+count*32).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int)session).putInt((int)boot).putInt((int)first).putInt((int)next).putInt((int)oldest);
        b.putInt((int)overflow).putInt((int)missed).putInt((int)errors);
        b.putInt((int)(0xffff_ff00L+next*40)).putInt(0xffff_ff00).putInt(flags==2?1000:0);
        b.putShort((short)flags).putShort((short)count);
        for(int i=0;i<count;i++) b.putInt((int)(0xffff_ff00L+(first+i)*40))
                .putFloat(0.1f).putFloat(-0.2f).putFloat(9.06f).putFloat(-7.0f).putFloat(0.6f).putFloat(0.1f).putFloat(35.2f);
        checksum(b.array());return b.array();
    }
    static void checksum(byte[] bytes) {
        CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-4);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length-4,(int)crc.getValue());
    }
    static ContinuousImuProtocol.Batch batch(long session,long boot,long first,int count,long next,long overflow,long missed,long errors,int flags) {
        return ContinuousImuProtocol.parseBody(body(session,boot,first,count,next,Math.min(first,next),overflow,missed,errors,flags));
    }
    static List<byte[]> fragments(byte[] body,int frame,int payloadBytes) {
        List<byte[]> output=new ArrayList<>();int parts=(body.length+payloadBytes-1)/payloadBytes;
        for(int i=0;i<parts;i++) {
            int count=Math.min(payloadBytes,body.length-i*payloadBytes);
            ByteBuffer fragment=ByteBuffer.allocate(8+count).order(ByteOrder.LITTLE_ENDIAN);
            fragment.put((byte)0xd7).put((byte)0x52).put((byte)1).put((byte)frame).put((byte)i).put((byte)parts).putShort((short)body.length);
            fragment.put(body,i*payloadBytes,count);output.add(fragment.array());
        }
        return output;
    }
    public static void main(String[] args) {
        byte[] vector="123456789".getBytes(StandardCharsets.US_ASCII);
        check(ContinuousImuProtocol.crc16(vector,vector.length)==0x29b1,"standard CCITT-FALSE vector");
        byte[] command=ContinuousImuProtocol.encodeControl(3,0xfedcba98L,0x12345678L,0x87654321L);
        check(command.length==18 && (command[0]&255)==0xd7 && command[1]==0x43 && command[2]==1 && command[3]==3,"control header");
        ByteBuffer control=ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN);
        check(Integer.toUnsignedLong(control.getInt(4))==0xfedcba98L && Integer.toUnsignedLong(control.getInt(8))==0x12345678L &&
                Integer.toUnsignedLong(control.getInt(12))==0x87654321L,"unsigned control fields and little endian");
        check(Short.toUnsignedInt(control.getShort(16))==ContinuousImuProtocol.crc16(command,16),"control CRC follows payload");
        invalid(()->ContinuousImuProtocol.encodeControl(0,1,1,1),"unknown opcode");
        invalid(()->ContinuousImuProtocol.encodeControl(1,-1,0,0),"negative session");
        invalid(()->ContinuousImuProtocol.encodeControl(1,1,0,0x100000000L),"argument uint32 overflow");

        byte[] bytes=body(0xfedcba98L,0x87654321L,0,8,8,0,4,5,6,1);
        ContinuousImuProtocol.Batch direct=ContinuousImuProtocol.parseBody(bytes);
        check(direct.session==0xfedcba98L && direct.boot==0x87654321L && direct.samples.size()==8,"maximum body and unsigned identifiers");
        check(direct.samples.get(0).gx==-7 && direct.samples.get(7).timestamp==24,"raw bias retained and board millis wraps unsigned");
        check(direct.overflow==4 && direct.missedSlots==5 && direct.readErrors==6 && direct.isActive(),"status counters retained");
        for(int payload:new int[]{12,32,96,300}) {
            ContinuousImuProtocol.Assembler assembler=new ContinuousImuProtocol.Assembler(2000);
            List<byte[]> parts=fragments(bytes,255,payload);Collections.reverse(parts);
            ContinuousImuProtocol.Batch actual=null;
            for(int i=0;i<parts.size();i++) {
                ContinuousImuProtocol.Batch candidate=assembler.accept(parts.get(i),100+i);
                if(candidate!=null)actual=candidate;
                else check(assembler.accept(parts.get(i),100+i)==null,"identical fragment duplicate remains partial");
            }
            check(actual!=null && actual.nextSeq==8 && actual.samples.size()==8,"out-of-order fragments at payload "+payload);
        }
        ContinuousImuProtocol.Assembler timeout=new ContinuousImuProtocol.Assembler(100);
        List<byte[]> parts=fragments(bytes,1,96);
        timeout.accept(parts.get(0),0);timeout.accept(parts.get(1),101);
        ContinuousImuProtocol.Batch complete=null;
        for(byte[] part:parts) { ContinuousImuProtocol.Batch b=timeout.accept(part,102);if(b!=null)complete=b; }
        check(complete!=null,"expired partial frame is discarded and retransmission completes");
        ContinuousImuProtocol.Assembler switched=new ContinuousImuProtocol.Assembler();
        switched.accept(parts.get(0),1);
        List<byte[]> replacement=fragments(bytes,2,96);
        complete=null;
        for(byte[] part:replacement) { ContinuousImuProtocol.Batch b=switched.accept(part,2);if(b!=null)complete=b; }
        check(complete!=null,"new frame id replaces one partial frame");
        byte[] corrupt=bytes.clone();corrupt[60]^=1;
        invalid(()->ContinuousImuProtocol.parseBody(corrupt),"CRC failure");
        byte[] nan=bytes.clone();ByteBuffer.wrap(nan).order(ByteOrder.LITTLE_ENDIAN).putFloat(52,Float.NaN);checksum(nan);
        invalid(()->ContinuousImuProtocol.parseBody(nan),"NaN sample cannot be accepted");
        invalid(()->ContinuousImuProtocol.parseBody(body(1,2,5,8,10,0,0,0,0,1)),"sample range exceeds board total");
        invalid(()->ContinuousImuProtocol.parseBody(body(1,2,0,0,0,0,0,0,0,3)),"active and stopped simultaneously");
        invalid(()->ContinuousImuProtocol.parseBody(new byte[309]),"body memory bound");
        byte[] oversized=parts.get(0).clone();oversized[6]=0x7f;oversized[7]=0x7f;
        invalid(()->new ContinuousImuProtocol.Assembler().accept(oversized,1),"fragment declared length bound");
        ContinuousImuProtocol.Assembler conflict=new ContinuousImuProtocol.Assembler();conflict.accept(parts.get(0),1);
        byte[] unequal=parts.get(0).clone();unequal[8]^=1;
        invalid(()->conflict.accept(unequal,2),"conflicting duplicate fragment");
        check(ContinuousImuProtocol.parseBody(body(1,2,19,0,20,19,0,0,0,2)).samples.isEmpty(),"zero-sample stopped status");
        System.out.println("Continuous IMU protocol: "+checks+" synthetic checks passed");
    }
}
