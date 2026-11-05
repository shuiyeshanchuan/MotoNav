package android.telephony;
public final class SmsMessage {
    private final int status;private SmsMessage(int status){this.status=status;}public static SmsMessage createFromPdu(byte[] pdu,String format){return pdu==null||pdu.length!=1?null:new SmsMessage(pdu[0]&255);}public static SmsMessage createFromPdu(byte[] pdu){return createFromPdu(pdu,"");}public int getStatus(){return status;}public boolean isStatusReportMessage(){return true;}
}
